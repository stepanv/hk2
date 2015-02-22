/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.hk2.xml.internal;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.xml.bind.annotation.XmlRootElement;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;

import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.utilities.general.GeneralUtilities;
import org.glassfish.hk2.utilities.reflection.ClassReflectionHelper;
import org.glassfish.hk2.utilities.reflection.Logger;
import org.glassfish.hk2.utilities.reflection.ReflectionHelper;
import org.glassfish.hk2.utilities.reflection.internal.ClassReflectionHelperImpl;
import org.glassfish.hk2.xml.internal.alt.AltMethod;
import org.glassfish.hk2.xml.internal.alt.clazz.ClassAltClassImpl;
import org.glassfish.hk2.xml.internal.alt.clazz.MethodAltMethodImpl;
import org.glassfish.hk2.xml.jaxb.internal.BaseHK2JAXBBean;

/**
 * @author jwells
 *
 */
public class JAUtilities {
    private final static boolean DEBUG_METHODS = Boolean.parseBoolean(GeneralUtilities.getSystemProperty(
            "org.jvnet.hk2.properties.xmlservice.jaxb.methods", "false"));
    
    public final static String GET = "get";
    public final static String SET = "set";
    public final static String IS = "is";
    public final static String LOOKUP = "lookup";
    public final static String ADD = "add";
    public final static String REMOVE = "remove";
    public final static String JAXB_DEFAULT_STRING = "##default";
    public final static String JAXB_DEFAULT_DEFAULT = "\u0000";
    
    private final static String CLASS_ADD_ON_NAME = "_$$_Hk2_Jaxb";
    private final static HashSet<String> DO_NOT_HANDLE_METHODS = new HashSet<String>();
    private final static String NO_CHILD_PACKAGE = "java.";
    
    private final HashMap<Class<?>, UnparentedNode> interface2NodeCache = new HashMap<Class<?>, UnparentedNode>();
    private final HashMap<Class<?>, UnparentedNode> proxy2NodeCache = new HashMap<Class<?>, UnparentedNode>();
    private final ClassPool defaultClassPool = ClassPool.getDefault(); // TODO:  We probably need to be more sophisticated about this
    private final CtClass superClazz;
    
    static {
        DO_NOT_HANDLE_METHODS.add("hashCode");
        DO_NOT_HANDLE_METHODS.add("equals");
        DO_NOT_HANDLE_METHODS.add("toString");
        DO_NOT_HANDLE_METHODS.add("annotationType");
    }
    
    /* package */ JAUtilities() {
        try {
            superClazz = defaultClassPool.get(BaseHK2JAXBBean.class.getName());
        }
        catch (NotFoundException e) {
            throw new MultiException(e);
        }
        
    }
    
    public synchronized UnparentedNode getNode(Class<?> type) {
        UnparentedNode retVal = proxy2NodeCache.get(type);
        if (retVal == null) return interface2NodeCache.get(type);
        return retVal;
    }
    
    public synchronized UnparentedNode convertRootAndLeaves(Class<?> root) {
        ClassReflectionHelper helper = new ClassReflectionHelperImpl();
        LinkedHashSet<Class<?>> needsToBeConverted = new LinkedHashSet<Class<?>>();
        
        getAllToConvert(root, needsToBeConverted, new HashSet<Class<?>>());
        
        if (DEBUG_METHODS) {
            Logger.getLogger().debug("Converting " + needsToBeConverted.size() + " nodes for root " + root.getName());
        }
        
        needsToBeConverted.removeAll(interface2NodeCache.keySet());
        
        LinkedList<UnparentedNode> contributions = new LinkedList<UnparentedNode>();
        for (Class<?> convertMe : needsToBeConverted) {
            UnparentedNode converted;
            try {
                converted = convert(convertMe, helper);
            }
            catch (RuntimeException re) {
                throw re;
            }
            catch (Throwable e) {
                throw new MultiException(e);
            }
            
            interface2NodeCache.put(convertMe, converted);
            contributions.add(converted);
        }
        
        for (UnparentedNode node : contributions) {
            for (ParentedNode child : node.getAllChildren()) {
                if (child.getChild().isPlaceholder()) {
                    UnparentedNode nonPlaceholder = interface2NodeCache.get(child.getChild().getOriginalInterface());
                    if (nonPlaceholder == null) {
                        throw new RuntimeException("The child of type " + child.getChild().getOriginalInterface().getName() +
                                " is unknown for " + node);  
                    }
                    
                    child.setChild(nonPlaceholder);
                }
            }
        }
        
        helper.dispose();
        return interface2NodeCache.get(root);
    }
    
    private UnparentedNode convert(Class<?> convertMe, ClassReflectionHelper helper) throws Throwable {
        Logger.getLogger().debug("XmlService converting " + convertMe.getName());
        UnparentedNode retVal = new UnparentedNode(convertMe);
        
        String targetClassName = convertMe.getName() + CLASS_ADD_ON_NAME;
        CtClass foundClass = defaultClassPool.getOrNull(targetClassName);
        
        if (foundClass == null) {
            CtClass generated = Generator.generate(
                    new ClassAltClassImpl(convertMe, helper), superClazz, defaultClassPool);
            
            generated.toClass(convertMe.getClassLoader(), convertMe.getProtectionDomain());
            
            foundClass = defaultClassPool.getOrNull(targetClassName);
        }
        
        Class<?> proxy = convertMe.getClassLoader().loadClass(targetClassName);
         
        XmlRootElement xre = convertMe.getAnnotation(XmlRootElement.class);
        if (xre != null) {        
            String rootName = Utilities.convertXmlRootElementName(xre, convertMe);
            retVal.setRootName(rootName);
        }
        
        ClassAltClassImpl altConvertMe = new ClassAltClassImpl(convertMe, helper);
        NameInformation xmlNameMap = Generator.getXmlNameMap(altConvertMe);
            
        HashMap<Class<?>, String> childTypes = new HashMap<Class<?>, String>();
        MethodInformation foundKey = null;
        for (AltMethod altMethodRaw : altConvertMe.getMethods()) {
            MethodAltMethodImpl altMethod = (MethodAltMethodImpl) altMethodRaw;
            
            Method originalMethod = altMethod.getOriginalMethod();
            MethodInformation mi = Generator.getMethodInformation(altMethod, xmlNameMap);
                
            if (DEBUG_METHODS) {
                Logger.getLogger().debug("Analyzing method " + mi + " of " + convertMe.getSimpleName());
            }
                
            if (mi.isKey()) {
                if (foundKey != null) {
                    throw new RuntimeException("Class " + convertMe.getName() + " has multiple key properties (" + originalMethod.getName() +
                            " and " + foundKey.getOriginalMethod().getName());
                }
                foundKey = mi;
                    
                retVal.setKeyProperty(mi.getRepresentedProperty());
            }
                
            boolean getterOrSetter = false;
            UnparentedNode childType = null;
            if (MethodType.SETTER.equals(mi.getMethodType())) {
                getterOrSetter = true;
                if (mi.getBaseChildType() != null) {
                    if (!interface2NodeCache.containsKey(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass())) {
                        // Must use a placeholder
                        childType = new UnparentedNode(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass(), true);
                    }
                    else {
                        childType = interface2NodeCache.get(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass());
                    }
                }
            }
            else if (MethodType.GETTER.equals(mi.getMethodType())) {
                getterOrSetter = true;
                if (mi.getBaseChildType() != null) {
                    if (!interface2NodeCache.containsKey(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass())) {
                        // Must use a placeholder
                        childType = new UnparentedNode(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass(), true);
                    }
                    else {
                        childType = interface2NodeCache.get(((ClassAltClassImpl) mi.getBaseChildType()).getOriginalClass());
                    }
                }
            }
                
            if (getterOrSetter) {
                if (childType != null) {
                    childTypes.put(childType.getOriginalInterface(), mi.getRepresentedProperty());
                        
                    retVal.addChild(mi.getRepresentedProperty(), mi.isList(), mi.isArray(), childType);
                }
                else {
                    retVal.addNonChildProperty(mi.getRepresentedProperty(), mi.getDefaultValue());
                }
            }
        }
            
        retVal.setTranslatedClass(proxy);
        proxy2NodeCache.put(proxy, retVal);
            
        return retVal;
    }
    
    private static void getAllToConvert(Class<?> toBeConverted,
            LinkedHashSet<Class<?>> needsToBeConverted,
            Set<Class<?>> cycleDetector) {
        if (needsToBeConverted.contains(toBeConverted)) return;
        
        if (cycleDetector.contains(toBeConverted)) return;
        cycleDetector.add(toBeConverted);
        
        try {
            // Find all the children
            for (Method method : toBeConverted.getMethods()) {
                if (Utilities.isGetter(method) == null) continue;
                
                Class<?> returnClass = method.getReturnType();
                if (returnClass.isInterface() && !(List.class.equals(returnClass))) {
                    // The assumption is that this is a non-instanced child
                    if (returnClass.getName().startsWith(NO_CHILD_PACKAGE)) continue;
                    
                    getAllToConvert(returnClass, needsToBeConverted, cycleDetector);
                    
                    continue;
                }
                
                if (returnClass.isArray()) {
                    Class<?> aType = returnClass.getComponentType();
                    
                    if (aType.isInterface()) {
                        getAllToConvert(aType, needsToBeConverted, cycleDetector);
                        
                        continue;
                    }
                }
                
                Type retType = method.getGenericReturnType();
                if (retType == null || !(retType instanceof ParameterizedType)) continue;
                
                Class<?> returnRawClass = ReflectionHelper.getRawClass(retType);
                if (returnRawClass == null || !List.class.equals(returnRawClass)) continue;
                
                Type listReturnType = ReflectionHelper.getFirstTypeArgument(retType);
                if (Object.class.equals(listReturnType)) continue;
                
                Class<?> childClass = ReflectionHelper.getRawClass(listReturnType);
                if (childClass == null || Object.class.equals(childClass)) continue;
                
                getAllToConvert(childClass, needsToBeConverted, cycleDetector);
            }
            
            needsToBeConverted.add(toBeConverted);
        }
        finally {
            cycleDetector.remove(toBeConverted);
        }
    }
}

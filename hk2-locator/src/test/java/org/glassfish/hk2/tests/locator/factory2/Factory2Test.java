/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
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
package org.glassfish.hk2.tests.locator.factory2;

import javax.inject.Singleton;

import org.junit.Assert;
import org.junit.Test;
import org.glassfish.hk2.api.Descriptor;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.FactoryDescriptors;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.tests.locator.utilities.LocatorHelper;
import org.glassfish.hk2.utilities.AbstractActiveDescriptor;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.hk2.utilities.DescriptorImpl;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;

/**
 * @author jwells
 *
 */
public class Factory2Test {
    public static final String ALICE = "Alice";
    public static final String BOB = "Bob";
    public static final String CAROL = "Carol";
    
    /**
     * Tests that a factory can generate new services
     * based on the identity of the lookup
     */
    @Test // @org.junit.Ignore
    public void testFactoryCanCorrelate() {
        ServiceLocator locator = LocatorHelper.getServiceLocator(CorrelatedServiceOne.class,
                CorrelatedServiceTwo.class,
                CorrelatedServiceThree.class,
                CorrelationFactory.class);
        
        CorrelatedServiceThree three = locator.getService(CorrelatedServiceThree.class);
        Assert.assertEquals(CAROL, three.getName());
        
        CorrelatedServiceOne one = locator.getService(CorrelatedServiceOne.class);
        Assert.assertEquals(ALICE, one.getName());
        
        CorrelatedServiceTwo two = locator.getService(CorrelatedServiceTwo.class);
        Assert.assertEquals(BOB, two.getName());
        
        // Twice because it is per lookup
        three = locator.getService(CorrelatedServiceThree.class);
        Assert.assertEquals(CAROL, three.getName());
    }
    
    /**
     * Tests that a factory can generate new services
     * based on the identity of the lookup.  This test
     * caused problems in the past because the unreified
     * ActiveDescriptor for the Factory tried to look
     * at the Injectees and failed for IllegalStateException
     */
    @Test // @org.junit.Ignore
    public void testFactoryCanCorrelateUnreifiedFactory() {
        ServiceLocator locator = LocatorHelper.getServiceLocator();
        ServiceLocatorUtilities.enableLookupExceptions(locator);
        
        DescriptorImpl correlatedOne = BuilderHelper.link(CorrelatedServiceOne.class.getName()).
                in(Singleton.class.getName()).
                qualifiedBy(Correlator.class.getName()).
                build();
        
        DescriptorImpl correlatedTwo = BuilderHelper.link(CorrelatedServiceTwo.class.getName()).
                in(Singleton.class.getName()).
                qualifiedBy(Correlator.class.getName()).
                build();
        
        DescriptorImpl correlatedThree = BuilderHelper.link(CorrelatedServiceThree.class.getName()).
                in(PerLookup.class.getName()).
                qualifiedBy(Correlator.class.getName()).
                build();
        
        ServiceLocatorUtilities.addOneDescriptor(locator, correlatedOne);
        ServiceLocatorUtilities.addOneDescriptor(locator, correlatedTwo);
        ServiceLocatorUtilities.addOneDescriptor(locator, correlatedThree);
        
        final AbstractActiveDescriptor<?> factoryDesc = BuilderHelper.activeLink(CorrelationFactory.class).
                to(Factory.class).
                in(Singleton.class).
                build();
        
        final AbstractActiveDescriptor<?> provideMethodDesc = BuilderHelper.activeLink(CorrelationFactory.class).
            to(PerLookupServiceWithName.class).
            in(PerLookup.class).
            buildProvideMethod();
        
        ServiceLocatorUtilities.addFactoryDescriptors(locator, new FactoryDescriptors() {

            @Override
            public Descriptor getFactoryAsAService() {
                return factoryDesc;
            }

            @Override
            public Descriptor getFactoryAsAFactory() {
                return provideMethodDesc;
            }
            
        });
        
        CorrelatedServiceThree three = locator.getService(CorrelatedServiceThree.class);
        Assert.assertEquals(CAROL, three.getName());
        
        CorrelatedServiceOne one = locator.getService(CorrelatedServiceOne.class);
        Assert.assertEquals(ALICE, one.getName());
        
        CorrelatedServiceTwo two = locator.getService(CorrelatedServiceTwo.class);
        Assert.assertEquals(BOB, two.getName());
        
        // Twice because it is per lookup
        three = locator.getService(CorrelatedServiceThree.class);
        Assert.assertEquals(CAROL, three.getName());
    }

}

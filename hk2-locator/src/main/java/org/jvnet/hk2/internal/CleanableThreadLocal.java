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
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
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
package org.jvnet.hk2.internal;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Extended ThreadLocal that provides a means for a cleanup of all the strong references to results of {@link #initialValue()}
 * that exist for all the living threads that accessed a value of given {@link ThreadLocal} instance which may cause a memory leak
 * in case of long living threads (e.g., servlet containers and their thread pools).
 * <p/>
 * Explicit thread local cleanup is known to be required in following cases (but is not limited to) in order to prevent memory
 * leaks:
 * <pre><ul>
 *     <li>The ThreadLocal instance is statically strongly referenced by class {@code C} and its value holds a transitive strong
 * reference to the ClassLoader that loaded the class {@code C}.</li>
 *     <li>The ThreadLocal instance is referenced by an instance {@code I} which is transitively statically referenced by class
 * {@code C}. The class {@code C} is transitively strongly referenced by the value of this thread local.</li>
 * </ul></pre>
 *
 * @author Stepan Vavra (stepan.vavra at oracle.com)
 */
class CleanableThreadLocal<T> extends ThreadLocal<T> {

    /**
     * A self cleanable set of weak references to all the threads that initialized the value of this ThreadLocal. For each such
     * thread, a strong reference to the value of {@link #initialValue()} is held in {@link java.lang.ThreadLocal.ThreadLocalMap}.
     * This entry is kept for the life time of the thread which may result in a memory leak for long living threads (e.g., servlet
     * containers with thread pools).
     */
    private final WeakHashMap<Thread, Void> accessingThreads = new WeakHashMap<Thread, Void>();

    @Override
    protected final T initialValue() {
        synchronized (this) {
            accessingThreads.put(Thread.currentThread(), null);
        }
        return cleanableInitialValue();
    }

    /**
     * Substitution to the original {@link #initialValue()} which is now hidden in order to safely store the accessing thread of
     * the initial value.
     *
     * @return the initial value for this thread-local
     */
    protected T cleanableInitialValue() {
        return null;
    }

    /**
     * Remove the ThreadLocal instance (that is weakly referenced) together with its associated value (the result of {@link
     * #cleanableInitialValue()}), which is strongly referenced, from all the existing threads (i.e., existing instances of {@link
     * Thread}) that accessed the value of this ThreadLocal.
     */
    public void cleanThreadLocal() {
        final List<Thread> threads;
        synchronized (this) {
            threads = new LinkedList<Thread>(accessingThreads.keySet());
            accessingThreads.clear();
        }
        try {
            cleanThreadLocal(threads);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot initiate thread locals cleanup do to reflection errors!", e);
        }
    }

    private void cleanThreadLocal(Collection<Thread> threads)
            throws NoSuchFieldException, ClassNotFoundException, NoSuchMethodException {
        // Thread.threadLocals are package-private, need to access it by using reflection
        Field threadFieldThreadLocals = Thread.class.getDeclaredField("threadLocals");
        threadFieldThreadLocals.setAccessible(true);

        // ThreadLocalMap is package-private, need to access its class object by reflection
        Class threadLocalMapClazz = Class.forName("java.lang.ThreadLocal$ThreadLocalMap");

        final Method threadLocalMapMethodRemove = threadLocalMapClazz.getDeclaredMethod("remove", ThreadLocal.class);
        threadLocalMapMethodRemove.setAccessible(true);

        for (Thread thread : threads) {
            try {
                cleanThreadLocal(thread, threadFieldThreadLocals, threadLocalMapMethodRemove);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("ThreadLocal cleanup ended with errors!", e);
            }
        }
    }

    private void cleanThreadLocal(Thread thread,
                                  Field threadFieldThreadLocals,
                                  Method threadLocalMapMethodRemove)
            throws IllegalAccessException, InvocationTargetException {

        Object threadLocalMapInstance = threadFieldThreadLocals.get(thread);
        if (threadLocalMapInstance == null) {
            // technically, thread local map cannot be null; but for the sake of code completeness...
            return;
        }
        threadLocalMapMethodRemove.invoke(threadLocalMapInstance, this);
    }
}

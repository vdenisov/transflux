/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.transflux.core.impl

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class StateMachineDefImplClassLoaderSpec extends Specification {

    private static final String RULE = "T(${StateMachineDefImplClassLoaderSpec.name}).ok(#entity)"

    /** Sees the JDK alone, so a type reference to a test class fails through it. */
    private static final ClassLoader BLIND = new URLClassLoader(new URL[0], (ClassLoader) null)

    private static final ClassLoader SIGHTED = StateMachineDefImplClassLoaderSpec.classLoader

    private final ClassLoader original = Thread.currentThread().contextClassLoader

    def cleanup() {
        Thread.currentThread().contextClassLoader = original
    }

    def 'an expression resolves a type through the class loader the definition names, whichever thread evaluates'() {
        given:
        def sm = machine { it.withClassLoader(SIGHTED) }

        when: 'it fires on a thread whose context class loader cannot see the type'
        Thread.currentThread().contextClassLoader = BLIND
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success

        cleanup:
        sm?.close()
    }

    def 'with none named, the context class loader at build is the one used, #when'() {
        given:
        Thread.currentThread().contextClassLoader = atBuild
        def sm = machine { }

        when:
        Thread.currentThread().contextClassLoader = atFiring
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success == succeeds

        cleanup:
        sm?.close()

        where:
        when                                  | atBuild | atFiring || succeeds
        'not the one at firing'               | SIGHTED | BLIND    || true
        'so a build that cannot see the type' | BLIND   | SIGHTED  || false
    }

    def 'a branch condition on a forked member resolves through it on the pool worker'() {
        given:
        def ran = new CountDownLatch(1)
        StateMachine<Order> sm = Transflux.defineStateMachine(Order)
            .withClassLoader(SIGHTED)
            .withStateResolver { Order o -> o.state }
            .withStateApplier { Order o, String s -> o.state = s }
            .state('a')
            .transition('t', 'a', 'b') { t ->
                t.forkOperation('f') { op ->
                    op.choice('c') { c -> c.branch('ok') { b -> b.conditionExpression(RULE).step('s') { e, ctx, tr -> ran.countDown() } } }
                }
            }
            .state('b')
            .build()

        when: 'the worker is created by, and inherits the context class loader of, a thread that cannot see the type'
        Thread.currentThread().contextClassLoader = BLIND
        sm.entity(new Order()).transitionTo('b')

        then:
        ran.await(5, TimeUnit.SECONDS)

        cleanup:
        sm?.close()
    }

    def 'a class loader is required'() {
        when:
        Transflux.defineStateMachine(Order).withClassLoader(null)

        then:
        thrown(TransfluxValidationException)
    }

    static boolean ok(Object entity) {
        return entity != null
    }

    private static StateMachine<Order> machine(Closure<?> configure) {
        StateMachineDef<Order> definition = Transflux.defineStateMachine(Order)
            .withStateResolver { Order o -> o.state }
            .withStateApplier { Order o, String s -> o.state = s }
            .state('a')
            .transition('t', 'a', 'b') { it.preConditionExpression(RULE) }
            .state('b')
        configure(definition)
        return definition.build()
    }

    static class Order {
        String state = 'a'
    }
}

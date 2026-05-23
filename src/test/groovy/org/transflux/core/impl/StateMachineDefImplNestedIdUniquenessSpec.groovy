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

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.TestContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer

class StateMachineDefImplNestedIdUniquenessSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class NoOpStep implements Step<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
        }
    }

    static class NoOpOperation implements Operation<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
        }
    }

    def 'step id and operation id share one namespace: step-then-operation rejected'() {
        given:
        def smd = baseDef({ t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
            c.operation('shared', new NoOpOperation())
        }) })
        smd.step('shared', new NoOpStep())

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('shared')
    }

    def 'step id and operation id share one namespace: operation-then-step rejected'() {
        given:
        def smd = baseDef({ t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
            c.operation('shared', new NoOpOperation())
            c.step('s1', new NoOpStep())
        }) })
        smd.step('shared', new NoOpStep())

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('shared')
    }

    def 'same operation id with different instances across two composites is rejected'() {
        given:
        def smd = multiTransitionDef(
            { t -> t.compositeOperation('outer1', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', new NoOpOperation())
            }) },
            { t -> t.compositeOperation('outer2', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', new NoOpOperation())
            }) })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('twin')
    }

    def 'same operation id with the same instance across two composites is tolerated (idempotent)'() {
        given:
        def shared = new NoOpOperation()
        def smd = multiTransitionDef(
            { t -> t.compositeOperation('outer1', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', shared)
            }) },
            { t -> t.compositeOperation('outer2', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', shared)
            }) })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'same operation id with the same class across two composites is tolerated (idempotent)'() {
        given:
        def smd = multiTransitionDef(
            { t -> t.compositeOperation('outer1', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', NoOpOperation)
            }) },
            { t -> t.compositeOperation('outer2', { CompositeOperationDef<Entity, TestContext> c ->
                c.operation('twin', NoOpOperation)
            }) })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'conditional-step id colliding with operation id is rejected'() {
        given:
        def smd = baseDef({ t -> t.compositeOperation('outer', { CompositeOperationDef<Entity, TestContext> c ->
            c.operation('twin', new NoOpOperation())
            c.conditional('twin', { ConditionalStepDef<Entity, TestContext> cs ->
                cs.branch('only', { BranchDef<Entity, TestContext> b ->
                    b.conditionExpression('true').step('inner-a')
                })
            })
        }) })
        smd.step('inner-a', new NoOpStep())

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('twin')
    }

    private static StateMachineDefImpl<Entity> baseDef(Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd
    }

    private static StateMachineDefImpl<Entity> multiTransitionDef(Consumer<TransitionDef<Entity, TestContext>> t1Config,
                                                                  Consumer<TransitionDef<Entity, TestContext>> t2Config) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't1', TestContext, t1Config) })
            .state('s2', { s -> s.transitionsTo('s3', 't2', TestContext, t2Config) })
            .state('s3', {})
        return smd
    }
}

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

package org.transflux.core.operation

import org.transflux.core.StateMachineDefImpl
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class NestedOperationMappingSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentCtx {
        String subscriptionId
        String billingStatus
        String activationResult
    }

    static class ChildCtx {
        String subscriptionId
        String activationResult
    }

    /** Reads `subscriptionId` from the child context and writes `activationResult` back. */
    static class ChildOp implements Operation<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, Transition<Entity, ChildCtx> transition) {
            context.activationResult = 'activated-' + context.subscriptionId
        }
    }

    /** Static class-form mapper — used to assert .withContextMapping(Class). */
    static class ParentChildMapper implements ContextMapper<ParentCtx, ChildCtx> {
        @Override
        ChildCtx mapTo(ParentCtx p) {
            def n = new ChildCtx()
            n.subscriptionId = p.subscriptionId
            return n
        }

        @Override
        void mapFrom(ParentCtx p, ChildCtx n) {
            p.activationResult = n.activationResult
        }
    }

    def 'class-based ContextMapper bridges parent and child context'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .withContextMapping(ParentChildMapper)
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-42')

        when:
        def result = sm.entity(entity).withContext(ctx).transitionTo('s2')

        then:
        result.success
        ctx.activationResult == 'activated-sub-42'
        result.executedStepIds.isEmpty()   // ChildOp didn't drive any framework steps
    }

    def 'instance-based ContextMapper bridges parent and child context'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .withContextMapping(new ParentChildMapper())
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-99')

        when:
        def result = sm.entity(entity).withContext(ctx).transitionTo('s2')

        then:
        result.success
        ctx.activationResult == 'activated-sub-99'
    }

    def 'inline mapTo + mapFrom lambdas bridge parent and child context'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .mapTo({ ParentCtx p ->
                        def n = new ChildCtx()
                        n.subscriptionId = p.subscriptionId
                        return n
                    })
                    .mapFrom({ ParentCtx p, ChildCtx n -> p.activationResult = n.activationResult })
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-inline')

        when:
        def result = sm.entity(entity).withContext(ctx).transitionTo('s2')

        then:
        result.success
        ctx.activationResult == 'activated-sub-inline'
    }

    def 'mapFrom is optional — omitting it means the child results do not flow back to the parent'() {
        given:
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('nested', ChildOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                op.usingContext(ChildCtx)
                    .mapTo({ ParentCtx p ->
                        def n = new ChildCtx()
                        n.subscriptionId = p.subscriptionId
                        return n
                    })
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new ParentCtx(subscriptionId: 'sub-no-back')

        when:
        def result = sm.entity(entity).withContext(ctx).transitionTo('s2')

        then:
        result.success
        ctx.activationResult == null   // nothing flowed back
    }

    def 'pass-through-with-explicit-empty-configurer behaves like the no-configurer overload'() {
        given:
        def passOp = new Operation<Entity, ParentCtx>() {
            @Override
            void execute(Entity entity, ParentCtx context, Transition<Entity, ParentCtx> transition) {
                context.billingStatus = 'reached'
            }
        }
        def smd = baseDef()
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, ParentCtx> c ->
            c.operation('pass', passOp, { NestedOperationDef<Entity, ParentCtx, ParentCtx> op ->
                // No usingContext, no mapping calls — pass-through.
                op.withName('pass-through-op')
            })
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new ParentCtx()

        when:
        def result = sm.entity(entity).withContext(ctx).transitionTo('s2')

        then:
        result.success
        ctx.billingStatus == 'reached'
    }

    private static StateMachineDefImpl<Entity, ParentCtx> baseDef() {
        def smd = new StateMachineDefImpl<Entity, ParentCtx>()
        smd.forEntityType(Entity)
            .forContextType(ParentCtx)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        return smd
    }
}

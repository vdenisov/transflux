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

import org.transflux.core.ContextScope
import org.transflux.core.Identifiable
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.CompositeOperationDef
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Predicate

class StateMachineDefImplContextSpec extends Specification {

    // ---- forContext block (grouping scope) ----

    def 'forContext block registers a step tagged with the scope context'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s-a', new StepA())
        })

        when:
        def sm = smd.build()

        then:
        sm != null
        smd.getComponentContextType('s-a') == CtxA
    }

    def 'multiple forContext blocks with the same context class accumulate registrations'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a', new StepA()) })
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a2', new StepA()) })

        when:
        smd.build()

        then:
        smd.getComponentContextType('s-a') == CtxA
        smd.getComponentContextType('s-a2') == CtxA
    }

    def 'two forContext blocks with different context classes coexist'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope -> scope.step('s-a', new StepA()) })
        smd.forContext(CtxB, { ContextScope<Entity, CtxB> scope -> scope.step('s-b', new StepB()) })

        when:
        smd.build()

        then:
        smd.getComponentContextType('s-a') == CtxA
        smd.getComponentContextType('s-b') == CtxB
    }

    def 'forContext registers an SM-level composite operation that can be referenced by id'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('inner-step', new StepA())
                .compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c ->
                    c.step('inner-step')
                })
        })

        when:
        smd.build()

        then:
        smd.getComponentContextType('outer') == CtxA
        smd.getSmCompositeOperation('outer') != null
    }

    // ---- Explicit Class<C> overloads on the flat SMD surface ----

    def 'step(id, Class<C>, Step) tags componentContextTypes identically to forContext scope'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('viaExplicit', CtxA, new StepA())
            .forContext(CtxA, { scope -> scope.step('viaScope', new StepA()) })

        expect:
        smd.getComponentContextType('viaExplicit') == CtxA
        smd.getComponentContextType('viaScope') == CtxA
    }

    def 'condition(id, Class<C>, Condition) tags componentContextTypes identically to forContext scope'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .condition('viaExplicit', CtxA, new CondA())
            .forContext(CtxA, { scope -> scope.condition('viaScope', new CondA()) })

        expect:
        smd.getComponentContextType('viaExplicit') == CtxA
        smd.getComponentContextType('viaScope') == CtxA
    }

    def 'compositeOperation(id, Class<C>, configurer) tags componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .compositeOperation('comp', CtxA, { c -> c.step('s', new StepA()) })

        expect:
        smd.getComponentContextType('comp') == CtxA
    }

    def 'conditionPredicate and conditionExpression with Class<C> tag componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .conditionPredicate('predCond', CtxA, { e -> true })
            .conditionExpression('exprCond', CtxA, 'true')

        expect:
        smd.getComponentContextType('predCond') == CtxA
        smd.getComponentContextType('exprCond') == CtxA
    }

    def 'untyped step/condition registrations leave componentContextTypes unset'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('plain', new StepA())
            .condition('plainCond', new CondA())

        expect:
        smd.getComponentContextType('plain') == null
        smd.getComponentContextType('plainCond') == null
    }

    def 'mixing explicit Class<C> with mismatched forContext on same id is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('mixed', CtxA, new StepA())

        when:
        smd.forContext(CtxB, { scope -> scope.step('mixed', new StepA() as Step<Entity, CtxB>) })

        then:
        thrown(TransfluxValidationException)
    }

    // ---- build-time context-compatibility check ----

    def 'SM-level composite referencing a step declared for the same context builds cleanly'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s', new StepA())
                .compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c -> c.step('s') })
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'SM-level composite referencing a step declared for a different context is rejected at build'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxB, { ContextScope<Entity, CtxB> scope -> scope.step('s', new StepB()) })
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.compositeOperation('outer', { CompositeOperationDef<Entity, CtxA> c ->
                c.step('s')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('outer')
        e.message.contains('s')
    }

    def 'legacy (untagged) registrations skip the context-compatibility check'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('legacy-step', new StepA())
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.compositeOperation('legacy-composite',
                    { CompositeOperationDef<Entity, Object> c -> c.step('legacy-step') })
            }) })
            .state('s2', {})

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    // ---- ContextScope Identifiable overloads ----

    @Unroll
    def 'ContextScope Identifiable overload accepted: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        smd.forContext(Object, { scope ->
            action.call(scope)
        })

        then:
        notThrown(Exception)

        where:
        variant                                          | action
        'step(Id, Step)'                                 | { s -> s.step(identifiable('s1'), new IdOverloadStep()) }
        'step(Id, Class)'                                | { s -> s.step(identifiable('s2'), IdOverloadStep) }
        'condition(Id, Condition)'                       | { s -> s.condition(identifiable('c1'), new IdOverloadCondition()) }
        'condition(Id, Class)'                           | { s -> s.condition(identifiable('c2'), IdOverloadCondition) }
        'condition(Id, Predicate)'                       | { s -> s.condition(identifiable('c3'), { e -> true } as Predicate) }
        'condition(Id, String)'                          | { s -> s.condition(identifiable('c4'), 'true') }
        'compositeOperation(Id, Consumer)'               | { s -> s.compositeOperation(identifiable('co1'), { c -> c.step('any') }) }
        'operation(Id, Operation)'                       | { s -> s.operation(identifiable('o1'), new IdOverloadOperation()) }
        'operation(Id, Class)'                           | { s -> s.operation(identifiable('o2'), IdOverloadOperation) }
    }

    @Unroll
    def 'ContextScope Identifiable overload rejects null: #variant'() {
        given:
        def smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)

        when:
        smd.forContext(Object, { scope ->
            action.call(scope)
        })

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                          | action
        'step(null, Step)'                               | { s -> s.step((Identifiable) null, new IdOverloadStep()) }
        'step(null, Class)'                              | { s -> s.step((Identifiable) null, IdOverloadStep) }
        'condition(null, Condition)'                     | { s -> s.condition((Identifiable) null, new IdOverloadCondition()) }
        'condition(null, Class)'                         | { s -> s.condition((Identifiable) null, IdOverloadCondition) }
        'condition(null, Predicate)'                     | { s -> s.condition((Identifiable) null, { e -> true } as Predicate) }
        'condition(null, String)'                        | { s -> s.condition((Identifiable) null, 'true') }
        'compositeOperation(null, Consumer)'             | { s -> s.compositeOperation((Identifiable) null, { c -> }) }
        'operation(null, Operation)'                     | { s -> s.operation((Identifiable) null, new IdOverloadOperation()) }
        'operation(null, Class)'                         | { s -> s.operation((Identifiable) null, IdOverloadOperation) }
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        return smd
    }

    private static Identifiable identifiable(String value) {
        return { -> value } as Identifiable
    }

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class CtxA { }

    static class CtxB { }

    static class StepA implements Step<Entity, CtxA> {
        @Override
        void execute(Entity entity, CtxA context, Transition<Entity, CtxA> transition) {
            entity.trail << 'step-a'
        }
    }

    static class StepB implements Step<Entity, CtxB> {
        @Override
        void execute(Entity entity, CtxB context, Transition<Entity, CtxB> transition) {
            entity.trail << 'step-b'
        }
    }

    static class CondA implements Condition<Entity, CtxA> {
        @Override
        boolean test(Entity entity, CtxA context, Transition<Entity, CtxA> transition) { true }
    }

    static class IdOverloadStep implements Step<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }

    static class IdOverloadCondition implements Condition<Object, Object> {
        @Override
        boolean test(Object e, Object c, Transition<Object, Object> t) { true }
    }

    static class IdOverloadOperation implements Operation<Object, Object> {
        @Override
        void execute(Object e, Object c, Transition<Object, Object> t) {}
    }
}

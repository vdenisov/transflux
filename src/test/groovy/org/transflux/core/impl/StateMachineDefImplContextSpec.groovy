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
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.action.ConditionalOperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.BiPredicate
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

    def 'forContext block registers a step via a configurer, tagged with the scope context'() {
        given:
        def smd = baseDef()
        def captured = null
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s-a', { d ->
                captured = d
                d.withName('N').using(new StepA())
            } as Consumer)
        })

        when:
        smd.build()

        then:
        smd.getComponentContextType('s-a') == CtxA

        and: 'the def carries the scope context and the metadata set inside the configurer'
        captured.getName() == 'N'
        captured.contextType() == CtxA
    }

    def 'the ContextScope step configurer and the flat step(id, Class, Consumer) register identically'() {
        given:
        def instance = new StepA()
        def viaScope = baseDef()
        viaScope.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('a', { d -> d.using(instance) } as Consumer)
        })
        def viaFlat = baseDef()
        viaFlat.step('a', CtxA, { d -> d.using(instance) } as Consumer)

        expect:
        viaScope.buildBoundActions()['a'].action.is(instance)
        viaFlat.buildBoundActions()['a'].action.is(instance)
        viaScope.getComponentContextType('a') == viaFlat.getComponentContextType('a')
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
                .operation('outer', { OperationDef<Entity, CtxA> c ->
                    c.run('inner-step')
                })
        })

        when:
        smd.build()

        then:
        smd.getComponentContextType('outer') == CtxA
        smd.getSmCompositeOperation('outer') != null
    }

    // ---- Explicit Class<C> overloads on the flat SMD surface ----

    def 'step(id, Class<C>, Action) tags componentContextTypes identically to forContext scope'() {
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

    def 'operation(id, Class<C>, configurer) tags componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .operation('comp', CtxA, { c -> c.step('s', new StepA()) })

        expect:
        smd.getComponentContextType('comp') == CtxA
    }

    def 'the typed predicate and expression conditions tag componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .condition('predCond', CtxA, { e -> true })
            .condition('exprCond', CtxA, 'true')

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
        smd.forContext(CtxB, { scope -> scope.step('mixed', new StepA() as Action<Entity, CtxB>) })

        then:
        thrown(TransfluxValidationException)
    }

    // ---- build-time context-compatibility check ----

    def 'SM-level composite referencing a step declared for the same context builds cleanly'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.step('s', new StepA())
                .operation('outer', { OperationDef<Entity, CtxA> c -> c.run('s') })
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
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.run('s')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('outer')
        e.message.contains('s')
    }

    @Unroll
    def 'an inline #form declaring a narrower context without a mapper is rejected at build'() {
        given: 'it runs pass-through, so a declared context may widen but never narrow'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> declare.call(c) })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains(label)
        e.message.contains(CtxB.name)

        where:
        form          | label                           | declare
        'operation'   | "operation 'inner'"             | narrowOperation(null)
        'step'        | "step 'inner'"                  | narrowStep(null)
        'conditional' | "conditional operation 'inner'" | narrowConditional(null)
    }

    def 'an inline container widening to Object is accepted'() {
        given: 'Object accepts the enclosing context, which is what pass-through requires'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.operation('inner', Object, { OperationDef<Entity, Object> nested ->
                    nested.step('s', new StepA())
                })
            })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    @Unroll
    def 'an inline #form declaring a narrower context with a mapper is accepted'() {
        given: 'the mapper produces the declared context, so nothing has to widen'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> declare.call(c) })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()

        where:
        form          | declare
        'operation'   | narrowOperation(aToB())
        'step'        | narrowStep(aToB())
        'conditional' | narrowConditional(aToB())
    }

    def 'a by-id reference to an inline member declaring its own context is checked against it'() {
        given: 'an inline id reaches no registration, so nothing else could supply its context'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> c
                .step('narrow', CtxB, aToB(), new StepB())
                .run('narrow') })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("'narrow'")
        e.message.contains(CtxB.name)
    }

    def 'a by-id reference out of a nested context to an enclosing inline member is checked too'() {
        given: 'the member takes the outer context, and the reference is made from the inner one'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> c
                .step('outer-step', new StepA())
                .operation('inner', CtxB, aToB(), { OperationDef<Entity, CtxB> n ->
                    n.run('outer-step')
                }) })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("'outer-step'")
        e.message.contains(CtxA.name)
    }

    def 'a by-id reference to an inline member with a mapper across the same boundary is accepted'() {
        given:
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> c
                .step('narrow', CtxB, aToB(), new StepB())
                .run('narrow', aToB()) })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    def 'a by-id reference to an inline member sharing the enclosing context stays legal'() {
        given: 'the common case - neither declares a context, so both take the scope it was opened for'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> c
                .step('plain', new StepA())
                .run('plain') })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    def 'a mapped inline container declaring Object runs its members against Object'() {
        given: 'the mapper produces the declared context, so nothing has to widen to reach it'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.operation('inner', Object, { CtxA a -> new Object() } as ContextMapper,
                            { OperationDef<Entity, Object> n ->
                                n.step('s', new AnyCtxStep()).run('s')
                            })
            })
        })

        when:
        smd.build()

        then: "the member's context is what 'inner' named, not what encloses it"
        noExceptionThrown()
    }

    def "a member of a container declaring Object is recorded against Object, not the enclosing context"() {
        given: 'the container runs pass-through, but javac typed its members from OperationDef<T, Object>'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.operation('mid', Object, { OperationDef<Entity, Object> mid -> mid
                    .step('leaf', new AnyCtxStep())
                    .operation('deep', CtxB, aToB(), { OperationDef<Entity, CtxB> d ->
                        d.run('leaf')
                    }) })
            })
        })

        when:
        smd.build()

        then: "CtxA is written nowhere near 'leaf', so it cannot be what a reference to it is checked against"
        noExceptionThrown()
    }

    def "the declarative and imperative dispatches into a container declaring Object agree"() {
        given: 'view.run consults the registry tag, so the recorded context has to be the same one'
        def seen = []
        def leaf = { Entity e, Object ctx, ExecutingTransition<Entity, Object> tr ->
            seen << ctx.class.simpleName
        } as Action
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', CtxA, { t ->
                t.operation('outer', { OperationDef<Entity, CtxA> c ->
                    c.operation('mid', Object, { OperationDef<Entity, Object> mid -> mid
                        .step('leaf', leaf)
                        .operation('deep', CtxB, aToB(), { OperationDef<Entity, CtxB> d -> d
                            .run('leaf')
                            .step('dispatch', { Entity e, CtxB ctx,
                                                ExecutingTransition<Entity, CtxB> tr ->
                                tr.run('leaf')
                            } as Action) }) })
                })
            }) })
            .state('s2', {})

        when:
        def result = smd.build().entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then: "in line it is handed what mid passed through, and both dispatches out of deep agree"
        result.success
        seen == ['CtxA', 'CtxB', 'CtxB']
    }

    def 'a by-id reference to an inline id in an unreachable nested scope reports visibility'() {
        given: 'no mapper can make it resolve, so answering on context would be wrong advice'
        def smd = baseDef()
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c -> c
                .operation('inner', CtxB, aToB(), { OperationDef<Entity, CtxB> n ->
                    n.step('buried', new StepB())
                })
                .run('buried') })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("unknown action id 'buried'")
        e.message.contains("composite 'inner'")
        !e.message.contains('Context type mismatch')
    }

    def "a branch condition is checked against the conditional's own context"() {
        given: 'a conditional may cross a boundary its branches then sit behind'
        def smd = baseDef()
        smd.condition('a-cond', CtxA, { Entity e, CtxA a -> true } as BiPredicate)
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.conditional('cond', CtxB, aToB(), { ConditionalOperationDef<Entity, CtxB> cs ->
                    cs.branch('b', { b -> b.condition('a-cond').step('s', new StepB()) })
                })
            })
        })

        when:
        smd.build()

        then: 'a condition takes no mapper, so there is no way to bridge it'
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("branch condition 'a-cond'")
        e.message.contains(CtxA.name)
    }

    def "a branch condition matching the conditional's own context is accepted"() {
        given:
        def smd = baseDef()
        smd.condition('b-cond', CtxB, { Entity e, CtxB b -> true } as BiPredicate)
        smd.forContext(CtxA, { ContextScope<Entity, CtxA> scope ->
            scope.operation('outer', { OperationDef<Entity, CtxA> c ->
                c.conditional('cond', CtxB, aToB(), { ConditionalOperationDef<Entity, CtxB> cs ->
                    cs.branch('b', { b -> b.condition('b-cond').step('s', new StepB()) })
                })
            })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    def 'an id declared in two scopes is answered from the one the reference resolves through'() {
        given: 'the same step declared in two containers, referenced across a boundary inside one'
        def shared = new StepA()
        def smd = baseDef()
        smd.operation('holder', CtxA, { OperationDef<Entity, CtxA> op -> op
            .step('shared-step', shared)
            .operation('inner', CtxB, aToB(), { OperationDef<Entity, CtxB> n -> n.run('shared-step') }) })
        smd.operation('other', CtxB, { OperationDef<Entity, CtxB> op -> op.step('shared-step', shared) })

        when:
        smd.build()

        then: "the entry consulted is holder's, not a flattened one the other declaration muddied"
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("'shared-step'")
        e.message.contains(CtxA.name)
    }

    def 'a duplicate inline id is reported as a duplicate, not as a context mismatch'() {
        given: 'the context pass runs before ids are claimed, so it must say nothing about a clash'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        smd.operation('a', CtxA, { OperationDef<Entity, CtxA> op -> op.step('dup', new StepA()).run('dup') })
        smd.operation('b', CtxB, { OperationDef<Entity, CtxB> op -> op.step('dup', new StepB()) })

        when:
        smd.build()

        then: "blaming 'a' for the context of 'b' would hide the fault and misname the culprit"
        def e = thrown(TransfluxValidationException)
        e.message.contains("'dup'")
        e.message.contains('already registered')
        !e.message.contains('Context type mismatch')
    }

    def 'legacy (untagged) registrations skip the context-compatibility check'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('legacy-step', new StepA())
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.operation('legacy-composite',
                    { OperationDef<Entity, Object> c -> c.run('legacy-step') })
            }) })
            .state('s2', {})

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def "a by-id member on a transition is context-checked like any other sequence member"() {
        given: 'the transition used to run its own attach-time check; now it is an ordinary member'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('narrow', CtxB, new StepB())
        smd.state('s1', { s -> s.transitionsTo('s2', 't', CtxA, { t -> t.run('narrow') }) })
        smd.state('s2', {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("transition 't'")
        e.message.contains("'narrow'")
        e.message.contains('without a mapper')
    }

    def "a mapper at a transition's call site bridges the boundary the slot could not"() {
        given: 'a transition attachment took no mapper at all, so this shape had no spelling'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('narrow', CtxB, new StepB())
        smd.state('s1', { s -> s.transitionsTo('s2', 't', CtxA, { t -> t.run('narrow', aToB()) }) })
        smd.state('s2', {})

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    def "a member declared on a transition against its own context runs pass-through"() {
        given: 'the declaration verbs carry their context shapes here too'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', CtxA, { t -> t
            .step('widened', Object, new AnyCtxStep())
            .operation('mapped', CtxB, aToB(), { OperationDef<Entity, CtxB> n ->
                n.step('inner', new StepB())
            }) }) })
        smd.state('s2', {})

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        return smd
    }


    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    private static ContextMapper<CtxA, CtxB> aToB() {
        return { CtxA parent -> new CtxB() } as ContextMapper
    }

    private static Closure narrowOperation(ContextMapper<CtxA, CtxB> mapper) {
        def body = { OperationDef<Entity, CtxB> n -> n.step('s', new StepB()) }
        return mapper == null
            ? { c -> c.operation('inner', CtxB, body) }
            : { c -> c.operation('inner', CtxB, mapper, body) }
    }

    private static Closure narrowStep(ContextMapper<CtxA, CtxB> mapper) {
        return mapper == null
            ? { c -> c.step('inner', CtxB, new StepB()) }
            : { c -> c.step('inner', CtxB, mapper, new StepB()) }
    }

    private static Closure narrowConditional(ContextMapper<CtxA, CtxB> mapper) {
        def body = { ConditionalOperationDef<Entity, CtxB> n ->
            n.branch('b', { b -> b.condition('always', alwaysTrue()).step('s', new StepB()) })
        }
        return mapper == null
            ? { c -> c.conditional('inner', CtxB, body) }
            : { c -> c.conditional('inner', CtxB, mapper, body) }
    }

    private static BiPredicate<Entity, CtxB> alwaysTrue() {
        return { Entity e, CtxB ctx -> true } as BiPredicate
    }

    static class AnyCtxStep implements Action<Entity, Object> {
        @Override
        void execute(Entity entity, Object context, ExecutingTransition<Entity, Object> transition) {
        }
    }

    static class CtxA { }

    static class CtxB { }

    static class StepA implements Action<Entity, CtxA> {
        @Override
        void execute(Entity entity, CtxA context, ExecutingTransition<Entity, CtxA> transition) {
            entity.trail << 'step-a'
        }
    }

    static class StepB implements Action<Entity, CtxB> {
        @Override
        void execute(Entity entity, CtxB context, ExecutingTransition<Entity, CtxB> transition) {
            entity.trail << 'step-b'
        }
    }

    static class CondA implements Condition<Entity, CtxA> {
        @Override
        boolean test(Entity entity, CtxA context, Transition transition) { true }
    }



}

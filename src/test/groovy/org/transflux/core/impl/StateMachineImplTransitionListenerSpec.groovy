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
import org.transflux.core.action.Compensation
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.TransitionExecution
import org.transflux.core.transition.TransitionListener
import org.transflux.core.transition.TransitionPhase
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplTransitionListenerSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class CountingListener implements TransitionListener<Entity, Object> {
        static int instances = 0

        CountingListener() {
            instances++
        }

        @Override
        void onTransition(Entity entity, Object context, TransitionExecution<Entity> execution) {
        }
    }

    def 'a successful transition notifies start then complete, and never error'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('a', recorder(log, 'start'))
                .onComplete('b', recorder(log, 'complete'))
                .onError('c', recorder(log, 'error')) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['start', 'complete']
    }

    @Unroll
    def 'a transition failing at #stage notifies start then error, and never complete'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('a', recorder(log, 'start'))
                .onComplete('b', recorder(log, 'complete'))
                .onError('c', recorder(log, 'error'))
                configure.call(t) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        log == ['start', 'error']

        where:
        stage              | configure
        'the operation'    | { t -> t.step('op', { e, ctx, tr -> throw new IllegalStateException('boom') } as Action) }
        'a post-condition' | { t -> t.postCondition('never', { e -> false } as Predicate) }
    }

    @Unroll
    def 'a transition rejected by a pre-condition that #outcome notifies nothing'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('a', recorder(log, 'start'))
                .onComplete('b', recorder(log, 'complete'))
                .onError('c', recorder(log, 'error'))
                .preCondition('gate', gate) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        log.isEmpty()

        where:
        outcome            | gate
        'returns false'    | { e -> false } as Predicate
        'throws'           | { e -> throw new IllegalStateException('gate blew up') } as Predicate
    }

    def "the transition's own listeners run before the global ones, each in declaration order"() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('own-1', recorder(log, 'own-1'))
                .onStart('own-2', recorder(log, 'own-2')) }) })
            .state('s2', {})
            .onAnyTransitionStart('any-1', recorder(log, 'any-1'))
            .onAnyTransitionStart('any-2', recorder(log, 'any-2')) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['own-1', 'own-2', 'any-1', 'any-2']
    }

    def 'a global listener fires for a transition that declares none of its own'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyTransitionStart('any-start', recorder(log, 'start'))
            .onAnyTransitionComplete('any-complete', recorder(log, 'complete')) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['start', 'complete']
    }

    def 'a class-form global listener is instantiated once, not once per transition'() {
        given:
        CountingListener.instances = 0

        when:
        build({ d -> d
            .state('s1', { st -> st
                .transitionsTo('s2', 't1', {})
                .transitionsTo('s3', 't2', {}) })
            .state('s2', {})
            .state('s3', {})
            .onAnyTransitionStart('counted', CountingListener) })

        then:
        CountingListener.instances == 1
    }

    def 'the transition hooks interleave with the state hooks in execution-flow order'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', stateRecorder(log, 'state-exit'))
                .transitionsTo('s2', 't', { t -> t
                    .onStart('a', recorder(log, 'transition-start'))
                    .onComplete('b', recorder(log, 'transition-complete'))
                    .step('op', { e, ctx, tr -> log << 'operation' } as Action) }) })
            .state('s2', { st -> st.onEntry('enter', stateRecorder(log, 'state-entry')) }) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['transition-start', 'state-exit', 'operation', 'transition-complete', 'state-entry']
    }

    def 'the completion payload carries the very result the caller receives'() {
        given:
        def captured = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onComplete('capture', capturing(captured)) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        captured.size() == 1

        and:
        TransitionExecution execution = captured.first()
        execution.phase() == TransitionPhase.COMPLETE
        execution.result().is(result)
        execution.firedBy() == null
        execution.transition().id == 't'
        execution.transition().sourceStateId == 's1'
        execution.transition().targetStateId == 's2'
    }

    def 'the start payload carries no result'() {
        given:
        def captured = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('capture', capturing(captured)) }) })
            .state('s2', {}) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        TransitionExecution execution = captured.first()
        execution.phase() == TransitionPhase.START
        execution.result() == null
    }

    def 'the error payload reports the failure and what was compensated'() {
        given:
        def captured = []
        def rolledBack = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onError('capture', capturing(captured))
                .operation('op', { c -> c
                    .step('undoable', compensatingStep(rolledBack))
                    .step('boom', { e, ctx, tr -> throw new IllegalStateException('boom') } as Action) }) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        rolledBack == ['undone']

        and:
        TransitionExecution execution = captured.first()
        execution.phase() == TransitionPhase.ERROR
        execution.result().is(result)
        execution.result().error.message == 'boom'
        execution.result().compensatedPath*.toString() == ['op/undoable']
    }

    @Unroll
    def 'firedBy names the #dispatch trigger that caused the transition'() {
        given:
        def captured = []
        def entity = new Entity('s1')
        def sm = triggerMachine(captured)

        when:
        fire.call(sm.entity(entity))

        then:
        TransitionExecution execution = captured.first()
        execution.firedBy() != null
        execution.firedBy().getId() == expectedTriggerId

        where:
        dispatch  | fire                                    || expectedTriggerId
        'manual'  | { it.fire('manual-go') }                || 'manual-go'
        'event'   | { it.processEvent('EVT', null) }        || 'event-go'
        'data'    | { it.processDataChange() }              || 'data-go'
    }

    def 'firedBy is null when the host invoked the transition directly'() {
        given:
        def captured = []
        def entity = new Entity('s1')
        def sm = triggerMachine(captured)

        when:
        sm.entity(entity).transitionTo('s2', 't-manual')

        then:
        captured.first().firedBy() == null
    }

    def 'the transition handed to a listener carries no way to dispatch actions'() {
        given:
        def failures = []
        def dispatchable = []
        def stepRuns = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .step('side-effect', { e, ctx, tr -> stepRuns << 'ran' } as Action)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('meddler', { e, ctx, ex ->
                    try {
                        dispatchable << (ex.transition() instanceof ExecutingTransition)
                        ex.transition().run('side-effect')
                    } catch (Exception caught) {
                        failures << caught.message
                    }
                } as TransitionListener) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        stepRuns.isEmpty()
        failures.size() == 1
        dispatchable == [false]
    }

    @Unroll
    def 'a throwing #hook listener leaves the outcome untouched'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('a', exploding(hook == 'onStart'))
                .onComplete('b', exploding(hook == 'onComplete')) }) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        result.error == null
        result.compensatedPath.isEmpty()
        entity.state == 's2'

        where:
        hook << ['onStart', 'onComplete']
    }

    def 'a throwing listener does not suppress the ones declared after it'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('boom', { e, ctx, ex -> throw new IllegalStateException('boom') } as TransitionListener)
                .onStart('after', recorder(log, 'after')) }) })
            .state('s2', {})
            .onAnyTransitionStart('global-after', recorder(log, 'global-after')) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['after', 'global-after']
    }

    def 'the typed context reaches a transition listener as declared'() {
        given:
        def seen = []
        def context = new TypedContext('audit')
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', TypedContext, { t -> t
                .onComplete('capture', { Entity e, TypedContext ctx, TransitionExecution ex -> seen << ctx.tag }
                    as TransitionListener) }) })
            .state('s2', {}) })

        when:
        sm.entity(entity).transitionTo('s2', context)

        then:
        seen == ['audit']
    }

    def 'a transition listener id colliding with a state listener id is rejected at build'() {
        when:
        build({ d -> d
            .state('s1', { st -> st
                .onEntry('dup', stateRecorder([], 'x'))
                .transitionsTo('s2', 't', { t -> t.onStart('dup', recorder([], 'y')) }) })
            .state('s2', {}) })

        then:
        def e = thrown(org.transflux.core.exception.TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered (declared on transition 't' via onStart)"
    }

    def 'a transition listener id reused on two transitions is rejected at build'() {
        when:
        build({ d -> d
            .state('s1', { st -> st
                .transitionsTo('s2', 't1', { t -> t.onStart('dup', recorder([], 'x')) })
                .transitionsTo('s3', 't2', { t -> t.onComplete('dup', recorder([], 'y')) }) })
            .state('s2', {})
            .state('s3', {}) })

        then: 'the message names the transition and hook that lost the race, since the stack points at build()'
        def e = thrown(org.transflux.core.exception.TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered (declared on transition 't2' via onComplete)"
    }

    def 'building the same definition twice does not report its own listeners as duplicates'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
           .withStateResolver({ e -> e.state } as StateResolver<Entity>)
           .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.onStart('once', recorder([], 'x')) }) })
           .state('s2', {})

        when:
        smd.build()
        smd.build()

        then:
        noExceptionThrown()
    }

    static class TypedContext {
        String tag

        TypedContext(String tag) {
            this.tag = tag
        }
    }

    private static StateMachine<Entity> triggerMachine(List captured) {
        return build({ d -> d
            .state('s1', { st -> st
                .transitionsTo('s2', 't-manual', { t -> t
                    .addManualTrigger('manual-go')
                    .onStart('capture-manual', capturing(captured)) })
                .transitionsTo('s3', 't-event', { t -> t
                    .addEventTrigger('event-go', 'EVT')
                    .onStart('capture-event', capturing(captured)) })
                .transitionsTo('s4', 't-data', { t -> t
                    .addDataTrigger('data-go', { dt -> dt.condition('always', { e -> true } as Predicate) })
                    .onStart('capture-data', capturing(captured)) }) })
            .state('s2', {})
            .state('s3', {})
            .state('s4', {}) })
    }

    private static TransitionListener<Entity, Object> recorder(List log, String label) {
        return { e, ctx, ex -> log << label } as TransitionListener
    }

    private static TransitionListener<Entity, Object> capturing(List captured) {
        return { e, ctx, ex -> captured << ex } as TransitionListener
    }

    private static TransitionListener<Entity, Object> exploding(boolean explode) {
        return { e, ctx, ex ->
            if (explode) {
                throw new IllegalStateException('listener blew up')
            }
        } as TransitionListener
    }

    private static StateListener<Entity> stateRecorder(List log, String label) {
        return { e, ctx, change -> log << label } as StateListener
    }

    private static Action<Entity, Object> compensatingStep(List rolledBack) {
        return new Action<Entity, Object>() {
            @Override
            void execute(Entity entity, Object context, ExecutingTransition<Entity, Object> transition) {
            }

            @Override
            Compensation<Entity, Object> getCompensation(Entity entity, Object context) {
                return { e, ctx -> rolledBack << 'undone' } as Compensation
            }
        }
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }
}

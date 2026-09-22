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
import org.transflux.core.action.Action
import org.transflux.core.action.ActionListener
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateMachineImplGlobalListenerDisableSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    def 'a state suppresses one global state listener and keeps its own and the rest'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyStateEntry('g-entry-1', stateRecorder(log, 'g1'))
            .onAnyStateEntry('g-entry-2', stateRecorder(log, 'g2'))
            .state('s1')
            .transition('t', 's1', 's2', {})
            .state('s2', { st -> st
                .onEntry('own-entry', stateRecorder(log, 'own'))
                .disableGlobalListener('g-entry-1') }) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log == ['own', 'g2']
    }

    def 'a state that disables nothing still sees every global'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyStateExit('g-exit', stateRecorder(log, 'exit-global'))
            .onAnyStateEntry('g-entry', stateRecorder(log, 'entry-global'))
            .state('s1')
            .transition('t', 's1', 's2', {})
            .state('s2', { st -> st.disableGlobalListener('g-entry') }) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then: 'the source state disabled nothing, so its exit hook still notifies'
        log == ['exit-global']
    }

    @Unroll
    def 'disableAllGlobalListeners on a state wins over a named disable, declared #order'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyStateEntry('g-entry-1', stateRecorder(log, 'g1'))
            .onAnyStateEntry('g-entry-2', stateRecorder(log, 'g2'))
            .state('s1')
            .transition('t', 's1', 's2', {})
            .state('s2', { st ->
                st.onEntry('own-entry', stateRecorder(log, 'own'))
                declaration.call(st) }) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then: 'the state machine-wide listeners are gone and the state own one remains'
        log == ['own']

        where:
        order           || declaration
        'named first'   || { it.disableGlobalListener('g-entry-1').disableAllGlobalListeners() }
        'blanket first' || { it.disableAllGlobalListeners().disableGlobalListener('g-entry-1') }
    }

    def 'declaring the same id twice is a no-op rather than an error'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyStateEntry('g-entry', stateRecorder(log, 'g'))
            .state('s1')
            .transition('t', 's1', 's2', {})
            .state('s2', { st -> st
                .disableGlobalListener('g-entry')
                .disableGlobalListener('g-entry') }) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log.isEmpty()
    }

    def 'the plural form suppresses each id it names, in every category'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyStateEntry('g-entry-1', stateRecorder(log, 'entry-1'))
            .onAnyStateEntry('g-entry-2', stateRecorder(log, 'entry-2'))
            .onAnyStateEntry('g-entry-3', stateRecorder(log, 'entry-3'))
            .onAnyTransitionStart('g-start-1', transitionRecorder(log, 'start-1'))
            .onAnyTransitionComplete('g-complete-1', transitionRecorder(log, 'complete-1'))
            .onAnyActionStart('g-action-1', actionRecorder(log, 'action-1'))
            .onAnyActionComplete('g-action-2', actionRecorder(log, 'action-2'))
            .onAnyActionError('g-action-3', actionRecorder(log, 'action-3'))
            .step('charge', { StepDef s -> s
                .using(noop())
                .disableGlobalListeners('g-action-1', 'g-action-2', 'g-action-3') } as Consumer)
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .disableGlobalListeners('g-start-1', 'g-complete-1')
                .run('charge') })
            .state('s2', { st -> st.disableGlobalListeners('g-entry-1', 'g-entry-3') }) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log == ['entry-2']
    }

    def 'the plural form naming no listener is refused'() {
        when:
        build({ d -> d
            .state('s1', { st -> st.disableGlobalListeners() }) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('disableAllGlobalListeners')
    }

    def 'a blank id in the plural form is refused'() {
        when:
        build({ d -> d
            .onAnyStateEntry('g-entry', stateRecorder([], 'g'))
            .state('s1', { st -> st.disableGlobalListeners('g-entry', ' ') }) })

        then:
        thrown(TransfluxValidationException)
    }

    def 'a transition suppresses one global transition listener, leaving a sibling transition alone'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyTransitionStart('g-start-1', transitionRecorder(log, 'g1'))
            .onAnyTransitionStart('g-start-2', transitionRecorder(log, 'g2'))
            .state('s1')
            .transition('quiet', 's1', 's2', { t -> t
                .onStart('own-start', transitionRecorder(log, 'own'))
                .disableGlobalListener('g-start-1') })
            .transition('loud', 's1', 's3', {})
            .state('s2')
            .state('s3') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log == ['own', 'g2']

        when:
        log.clear()
        sm.entity(new Entity('s1')).transitionTo('s3')

        then:
        log == ['g1', 'g2']
    }

    def 'the category follows the owner - a transition disable leaves state and action globals alone'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyTransitionStart('g-transition', transitionRecorder(log, 'transition-global'))
            .onAnyStateEntry('g-state', stateRecorder(log, 'state-global'))
            .onAnyActionStart('g-action', actionRecorder(log, 'action-global'))
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .disableAllGlobalListeners()
                .step('work', noop()) })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log == ['action-global', 'state-global']
    }

    def 'an action suppresses one global action listener at every call site it is reached from'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyActionStart('g-action-1', actionRecorder(log, 'g1'))
            .onAnyActionStart('g-action-2', actionRecorder(log, 'g2'))
            .step('charge', { StepDef s -> s
                .using(noop())
                .onStart('own-action', actionRecorder(log, 'own'))
                .disableGlobalListener('g-action-1') } as Consumer)
            .state('s1')
            .transition('t1', 's1', 's2', { t -> t.run('charge') })
            .transition('t2', 's1', 's3', { t -> t.run('charge') })
            .state('s2')
            .state('s3') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        log == ['own', 'g2']

        when: 'the same action is reached from another transition'
        log.clear()
        sm.entity(new Entity('s1')).transitionTo('s3')

        then: 'the disable rides on the action, not on the call site'
        log == ['own', 'g2']
    }

    def 'a container disabling its globals does not disable its members'() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyActionStart('g-action', { e, ctx, x -> log << x.actionId() } as ActionListener)
            .operation('outer', Object, { OperationDef o -> o
                .disableAllGlobalListeners()
                .step('inner', noop()) } as Consumer)
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.run('outer') })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then: 'an action carries its own observers, and a disable is not inherited'
        log == ['inner']
    }

    private static Action<Entity, Object> noop() {
        return { e, ctx, tr -> } as Action
    }

    private static StateListener<Entity> stateRecorder(List log, String label) {
        return { e, ctx, change -> log << label } as StateListener
    }

    private static TransitionListener<Entity, Object> transitionRecorder(List log, String label) {
        return { e, ctx, execution -> log << label } as TransitionListener
    }

    private static ActionListener<Entity, Object> actionRecorder(List log, String label) {
        return { e, ctx, execution -> log << label } as ActionListener
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

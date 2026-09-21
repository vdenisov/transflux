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
import org.transflux.core.logging.ExecutionLogging
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateMachineDefImplGlobalListenerDisableSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    @Unroll
    def 'an id naming no global #category listener fails the build'() {
        when:
        build(declaration)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'no-such-listener'")
        e.message.contains(owner)
        e.message.contains("no global ${category} listener")

        where:
        category     | owner            || declaration
        'state'      | "state 's2'"     || { d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st.disableGlobalListener('no-such-listener') }) }
        'transition' | "transition 't'" || { d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.disableGlobalListener('no-such-listener') }) })
            .state('s2', {} as Consumer) }
        'action'     | "step 'charge'"  || { d -> d
            .step('charge', { StepDef s -> s
                .using(noop())
                .disableGlobalListener('no-such-listener') } as Consumer)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.run('charge') }) })
            .state('s2', {} as Consumer) }
    }

    def 'an id naming a global listener of another category fails the build'() {
        when:
        build({ d -> d
            .onAnyActionStart('g-action', { e, ctx, x -> } as ActionListener)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.disableGlobalListener('g-action') }) })
            .state('s2', {} as Consumer) })

        then: 'the category follows the owner, so a transition cannot name an action listener'
        def e = thrown(TransfluxValidationException)
        e.message.contains("'g-action'")
        e.message.contains('no global transition listener')
    }

    def 'an id naming an owner-attached listener fails the build'() {
        when:
        build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .onStart('own-start', { e, ctx, x -> } as TransitionListener)
                .disableGlobalListener('own-start') }) })
            .state('s2', {} as Consumer) })

        then: "an owner's own listeners are the consent, and are never suppressed"
        def e = thrown(TransfluxValidationException)
        e.message.contains("'own-start'")
        e.message.contains('no global transition listener')
    }

    def 'a nested inline declaration is reached by the check'() {
        when:
        build({ d -> d
            .operation('outer', Object, { OperationDef o -> o
                .step('inner', { StepDef s -> s
                    .using(noop())
                    .disableGlobalListener('no-such-listener') } as Consumer) } as Consumer)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.run('outer') }) })
            .state('s2', {} as Consumer) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 'inner'")
    }

    def 'the blanket form needs no registered global at all'() {
        when:
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t
                .disableAllGlobalListeners() }) })
            .state('s2', {} as Consumer) })

        then:
        sm != null
    }

    def 'the ids withExecutionLogging registers can be named by the matching category'() {
        when:
        def sm = build({ d -> d
            .withExecutionLogging(ExecutionLogging.defaults())
            .state('s1', { st -> st
                .disableGlobalListener('transflux-log-state-exit')
                .transitionsTo('s2', 't', { t -> t
                    .disableGlobalListener('transflux-log-transition-start')
                    .step('work', { StepDef s -> s
                        .using(noop())
                        .disableGlobalListener('transflux-log-action-start')
                        .disableGlobalListener('transflux-log-action-complete') } as Consumer) }) })
            .state('s2', {} as Consumer) })

        then:
        sm != null
    }

    private static Action<Entity, Object> noop() {
        return { e, ctx, tr -> } as Action
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

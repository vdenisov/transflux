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

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

class StateMachineImplIdentifiableOverloadsSpec extends Specification {

    static enum State implements Identifiable {
        S1, S2

        @Override
        String getId() { name() }
    }

    static enum TransitionId implements Identifiable {
        T1

        @Override
        String getId() { name() }
    }

    static class Entity {
        String state

        Entity(String state) { this.state = state }
    }

    static class Ctx {
        String input
    }

    private StateMachine<Entity> sm

    void setup() {
        sm = Transflux.<Entity> defineStateMachine()
            .forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('S1', { s -> s.transitionsTo('S2', 'T1', Ctx, { t -> }) })
            .state('S2', {})
            .build()
    }

    @Unroll
    def 'EntityBinding.transitionTo #variant succeeds with Identifiable args'() {
        given:
        def entity = new Entity('S1')
        def ctx = new Ctx(input: 'foo')

        when:
        def result = action.call(sm.entity(entity), ctx)

        then:
        result.success
        result.targetStateId == 'S2'

        where:
        variant                                       | action
        '(Identifiable)'                              | { b, c -> b.transitionTo(State.S2) }
        '(Identifiable, Identifiable)'                | { b, c -> b.transitionTo(State.S2, TransitionId.T1) }
        '(Identifiable, String)'                      | { b, c -> b.transitionTo(State.S2, 'T1') }
        '(String, Identifiable)'                      | { b, c -> b.transitionTo('S2', TransitionId.T1) }
        '(Identifiable, Object)'                      | { b, c -> b.transitionTo(State.S2, c) }
        '(Identifiable, Identifiable, Object)'        | { b, c -> b.transitionTo(State.S2, TransitionId.T1, c) }
        '(Identifiable, String, Object)'              | { b, c -> b.transitionTo(State.S2, 'T1', c) }
        '(String, Identifiable, Object)'              | { b, c -> b.transitionTo('S2', TransitionId.T1, c) }
    }

    @Unroll
    def 'StateMachine.executeTransition #variant succeeds with Identifiable args'() {
        given:
        def entity = new Entity('S1')

        when:
        def result = action.call(sm, entity)

        then:
        result.success
        result.targetStateId == 'S2'

        where:
        variant                                  | action
        '(entity, Identifiable)'                 | { s, e -> s.executeTransition(e, State.S2) }
        '(entity, Identifiable, Identifiable)'   | { s, e -> s.executeTransition(e, State.S2, TransitionId.T1) }
        '(entity, Identifiable, String)'         | { s, e -> s.executeTransition(e, State.S2, 'T1') }
        '(entity, String, Identifiable)'         | { s, e -> s.executeTransition(e, 'S2', TransitionId.T1) }
    }

    @Unroll
    def 'EntityBinding.transitionTo #variant rejects null Identifiable'() {
        given:
        def binding = sm.entity(new Entity('S1'))

        when:
        action.call(binding)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                | action
        '(null)'                               | { b -> b.transitionTo((Identifiable) null) }
        '(null, identifiable)'                 | { b -> b.transitionTo((Identifiable) null, TransitionId.T1) }
        '(null, string)'                       | { b -> b.transitionTo((Identifiable) null, 'T1') }
        '(string, null)'                       | { b -> b.transitionTo('S2', (Identifiable) null) }
        '(null, object)'                       | { b -> b.transitionTo((Identifiable) null, (Object) null) }
        '(null, identifiable, object)'         | { b -> b.transitionTo((Identifiable) null, TransitionId.T1, (Object) null) }
        '(null, string, object)'               | { b -> b.transitionTo((Identifiable) null, 'T1', (Object) null) }
        '(string, null, object)'               | { b -> b.transitionTo('S2', (Identifiable) null, (Object) null) }
    }

    @Unroll
    def 'StateMachine.executeTransition #variant rejects null Identifiable'() {
        given:
        def entity = new Entity('S1')

        when:
        action.call(sm, entity)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        '(entity, null)'                         | { s, e -> s.executeTransition(e, (Identifiable) null) }
        '(entity, null, identifiable)'           | { s, e -> s.executeTransition(e, (Identifiable) null, TransitionId.T1) }
        '(entity, null, string)'                 | { s, e -> s.executeTransition(e, (Identifiable) null, 'T1') }
        '(entity, string, null)'                 | { s, e -> s.executeTransition(e, 'S2', (Identifiable) null) }
    }
}

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
import org.transflux.core.StateMachineDef
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

class DataTriggerDefImplSpec extends Specification {

    static class Entity {
        String state
        int priority

        Entity(String state, int priority) {
            this.state = state
            this.priority = priority
        }
    }

    static class HighPriority implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition transition) {
            return entity.priority > 5
        }
    }

    @Unroll
    def 'a data-trigger gate in the #form form gates firing'() {
        given:
        def sm = build(setup)

        expect:
        sm.entity(new Entity('s1', 9)).processDataChange().fired()
        !sm.entity(new Entity('s1', 3)).processDataChange().fired()

        where:
        form              | setup
        'reference'       | { StateMachineDef d -> d.condition('high', { e -> ((Entity) e).priority > 5 } as Predicate)
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('high') }) }) })
                                .state('s2', {}) }
        'reference (Id)'  | { StateMachineDef d -> d.condition('high', { e -> ((Entity) e).priority > 5 } as Predicate)
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition(idOf('high')) }) }) })
                                .state('s2', {}) }
        'instance'        | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('inst', new HighPriority()) }) }) })
                                .state('s2', {}) }
        'class'           | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('cls', HighPriority) }) }) })
                                .state('s2', {}) }
        'BiPredicate'     | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('bip', { e, c -> ((Entity) e).priority > 5 } as BiPredicate) }) }) })
                                .state('s2', {}) }
        'Predicate'       | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('pred', { e -> ((Entity) e).priority > 5 } as Predicate) }) }) })
                                .state('s2', {}) }
        'expression'      | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.condition('expr', 'priority > 5') }) }) })
                                .state('s2', {}) }
        'auto-id expr'    | { StateMachineDef d -> d
                                .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.conditionExpression('priority > 5') }) }) })
                                .state('s2', {}) }
    }

    def 'a data trigger declaring no condition is rejected at build'() {
        when:
        build({ StateMachineDef d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t -> t.addDataTrigger('dt', { dt -> dt.withName('No gate') }) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'dt'")
        e.message.contains('no condition')
    }

    def 'mutating the data-trigger def after its configurer returns is rejected'() {
        given:
        DataTriggerDefImpl<Entity, TestContext> escaped = null

        when:
        build({ StateMachineDef d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t -> t.addDataTrigger('dt', { dt ->
                escaped = (DataTriggerDefImpl<Entity, TestContext>) dt
                dt.condition('any', { e -> true } as Predicate) }) }) })
            .state('s2', {}) })
        escaped.condition('late', { e -> true } as Predicate)

        then:
        thrown(TransfluxValidationException)
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
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

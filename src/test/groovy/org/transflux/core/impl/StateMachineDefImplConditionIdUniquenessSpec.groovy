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
import org.transflux.core.TestContext
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Covers inline condition ids competing for the per-build canonical id table, which is what makes
 * the documented SM-wide id-uniqueness rule apply to conditions as it already does to steps,
 * operations and conditional steps.
 */
class StateMachineDefImplConditionIdUniquenessSpec extends Specification {

    @Unroll
    def 'the same inline condition id declared twice with different payloads is rejected: #site'() {
        when:
        build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', first)
            .transitionsTo('s3', 't2', second) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Condition id 'shared'")
        e.message.contains('already registered')

        where:
        site               | first                                                                          | second
        'pre-conditions'   | { t -> t.preCondition('shared', { en -> true } as Predicate) }                  | { t -> t.preCondition('shared', { en -> false } as Predicate) }
        'post-conditions'  | { t -> t.postCondition('shared', { en -> true } as Predicate) }                 | { t -> t.postCondition('shared', { en -> false } as Predicate) }
        'pre then post'    | { t -> t.preCondition('shared', { en -> true } as Predicate) }                  | { t -> t.postCondition('shared', { en -> false } as Predicate) }
        'manual triggers'  | { t -> t.addManualTrigger('m1', { mt -> mt.preCondition('shared', { en -> true } as Predicate) }) } | { t -> t.addManualTrigger('m2', { mt -> mt.preCondition('shared', { en -> false } as Predicate) }) }
        'data gates'       | { t -> t.addDataTrigger('d1', { dt -> dt.condition('shared', { en -> true } as Predicate) }) }      | { t -> t.addDataTrigger('d2', { dt -> dt.condition('shared', { en -> false } as Predicate) }) }
    }

    def 'an inline condition id colliding with a registered step id is rejected'() {
        when:
        build({ d -> d
            .step('shared', { e, c, tr -> } as Step)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.preCondition('shared', { e -> true } as Predicate) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'shared'")
        e.message.contains('already registered')
    }

    def 'an inline condition id colliding with an SM-level registered condition is rejected'() {
        when:
        build({ d -> d
            .condition('shared', { e -> true } as Predicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.preCondition('shared', { e -> false } as Predicate) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'shared'")
        e.message.contains('already registered')
    }

    def 'the same condition instance under the same id is idempotent'() {
        given:
        def shared = new AlwaysTrue()

        when:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.preCondition('shared', shared) })
            .transitionsTo('s3', 't2', { t -> t.preCondition('shared', shared) }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        sm != null
    }

    def 'the same condition class under the same id is idempotent'() {
        when:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.preCondition('shared', AlwaysTrue) })
            .transitionsTo('s3', 't2', { t -> t.preCondition('shared', AlwaysTrue) }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        sm != null
    }

    def 'the same expression under the same id is idempotent'() {
        when:
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.preCondition('shared', 'true') })
            .transitionsTo('s3', 't2', { t -> t.preCondition('shared', 'true') }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        sm != null
    }

    def 'a different expression under the same id is rejected'() {
        when:
        build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.preCondition('shared', 'true') })
            .transitionsTo('s3', 't2', { t -> t.preCondition('shared', 'false') }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Condition id 'shared'")
    }

    def 'referencing one registered condition from many sites stays legal'() {
        when: 'both transitions reference the same registered id rather than declaring it'
        def sm = build({ d -> d
            .condition('registered', { e -> true } as Predicate)
            .state('s1', { st -> st
                .transitionsTo('s2', 't1', { t -> t.preCondition('registered') })
                .transitionsTo('s3', 't2', { t -> t.preCondition('registered') }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        sm != null
    }

    def 'id-less expression conditions never collide'() {
        when: 'two sites declare the same expression with no explicit id'
        def sm = build({ d -> d.state('s1', { st -> st
            .transitionsTo('s2', 't1', { t -> t.preConditionExpression('true') })
            .transitionsTo('s3', 't2', { t -> t.preConditionExpression('true') }) })
            .state('s2', {})
            .state('s3', {}) })

        then:
        sm != null
    }

    def 'branch conditions take part in id uniqueness'() {
        when:
        build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', { t ->
                t.preCondition('shared', { e -> true } as Predicate)
                 .compositeOperation('comp', { c -> c.conditional('cond', { cs -> cs
                     .branch('b1', { b -> b
                         .condition('shared', { e -> false } as Predicate)
                         .step('s', { en, c2, tr -> } as Step) }) }) }) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'shared'")
        e.message.contains('already registered')
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class AlwaysTrue implements Condition<Entity, TestContext> {
        @Override
        boolean test(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            return true
        }
    }
}

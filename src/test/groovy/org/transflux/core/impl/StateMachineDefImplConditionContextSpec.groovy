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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.BiPredicate
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Covers the build-time check that a by-id condition reference's declared context type can accept
 * the context of the site referencing it.
 */
class StateMachineDefImplConditionContextSpec extends Specification {

    @Unroll
    def 'a #site referencing a condition declared for an incompatible context is rejected at build'() {
        when:
        build({ d -> d
            .conditionPredicate('paid', OtherContext, { e, c -> true } as BiPredicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, reference) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith('Context type mismatch')
        e.message.contains("'paid'")
        e.message.contains(TestContext.name)
        e.message.contains(OtherContext.name)
        e.message.contains(expectedKind)

        where:
        site                   | reference                                                              || expectedKind
        'transition pre-condition'  | { t -> t.preCondition('paid') }                                   || 'pre-condition'
        'transition post-condition' | { t -> t.postCondition('paid') }                                  || 'post-condition'
        'manual trigger'       | { t -> t.addManualTrigger('mt', { mt -> mt.preCondition('paid') }) }   || 'pre-condition'
        'data trigger gate'    | { t -> t.addDataTrigger('dt', { dt -> dt.condition('paid') }) }        || 'gate condition'
    }

    def 'the rejection names the offending site so the reference can be found'() {
        when:
        build({ d -> d
            .conditionPredicate('paid', OtherContext, { e, c -> true } as BiPredicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addDataTrigger('ready', { dt -> dt.condition('paid') }) }) })
            .state('s2', {}) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("data trigger 'ready'")
    }

    def 'a reference to a condition registered for the same context is accepted'() {
        when:
        def sm = build({ d -> d
            .conditionPredicate('paid', TestContext, { e, c -> true } as BiPredicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addDataTrigger('dt', { dt -> dt.condition('paid') }) }) })
            .state('s2', {}) })

        then:
        sm != null
    }

    def 'a condition registered against a supertype context accepts a subtype call site'() {
        when:
        def sm = build({ d -> d
            .conditionPredicate('paid', BaseContext, { e, c -> true } as BiPredicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', DerivedContext, { t ->
                t.addDataTrigger('dt', { dt -> dt.condition('paid') }) }) })
            .state('s2', {}) })

        then:
        sm != null
    }

    def 'a condition registered without a declared context type is not checked'() {
        when: 'the untyped registration overload leaves the condition untagged'
        def sm = build({ d -> d
            .condition('paid', { e -> true } as Predicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addDataTrigger('dt', { dt -> dt.condition('paid') }) }) })
            .state('s2', {}) })

        then: 'it still builds, so existing untyped definitions keep working'
        sm != null
    }

    def 'inline condition forms are not subject to the reference check'() {
        when: 'the gate is declared inline against the transition own context'
        def sm = build({ d -> d
            .conditionPredicate('paid', OtherContext, { e, c -> true } as BiPredicate)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addDataTrigger('dt', { dt -> dt.condition('inline', { e -> true } as Predicate) }) }) })
            .state('s2', {}) })

        then:
        sm != null
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

    static class OtherContext {
        String other
    }

    static class BaseContext {
        String base
    }

    static class DerivedContext extends BaseContext {
        String derived
    }
}

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

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.transflux.core.transition.Transition
import spock.lang.Specification

/**
 * Structural prep for child-context binding (Phase 2.5 §2.5.4 / requirements §4.5.2.5).
 *
 * <p>The Condition contract already takes context as an explicit parameter on each
 * {@link Condition#test} call, so any future caller wiring nested-operation pre/post
 * conditions can pass the child's context at evaluation time without touching the
 * condition-evaluator code path. These specs lock that contract in.
 */
class ConditionContextHookSpec extends Specification {

    static class Entity {
        String tier
    }

    static class Ctx {
        int counter
    }

    def 'predicate-based condition (entity-only) ignores context — same result for any context'() {
        given:
        def predicate = { Entity e -> e.tier == 'VIP' } as java.util.function.Predicate<Entity>
        def cond = (Condition<Entity, Ctx>) ((entity, ctx, txn) -> predicate.test(entity))
        def entity = new Entity(tier: 'VIP')

        expect:
        cond.test(entity, new Ctx(counter: 1), null)
        cond.test(entity, new Ctx(counter: 999), null)
        cond.test(entity, null, null)
    }

    def 'expression-based condition reads context — different contexts produce different results'() {
        given:
        def cond = BoundCondition.<Entity, Ctx> fromExpression('cond-id', '#context.counter > 5').condition()
        def entity = new Entity()

        expect:
        !cond.test(entity, new Ctx(counter: 3), null)
        cond.test(entity, new Ctx(counter: 10), null)
    }

    def 'instance-based condition reads context — caller-supplied context is honored on every invocation'() {
        given:
        def seenContexts = []
        def cond = (Condition<Entity, Ctx>) ((entity, ctx, txn) -> {
            seenContexts << ctx.counter
            return ctx.counter > 0
        })
        def entity = new Entity()
        def ctxA = new Ctx(counter: 1)
        def ctxB = new Ctx(counter: 42)

        when:
        def resultA = cond.test(entity, ctxA, null)
        def resultB = cond.test(entity, ctxB, null)

        then:
        resultA
        resultB
        seenContexts == [1, 42]
    }
}

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
//file:noinspection GroovyPointlessBoolean

package org.transflux.core.impl

import org.transflux.core.action.Compensation
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ActionPath
import spock.lang.Specification

import java.util.function.Predicate

class BoundCompensationRouterSpec extends Specification {

    private static final ActionPath PATH = ActionPath.of('act')

    def 'always() builds a router with no routes and the supplied fallback'() {
        given:
        def compensation = noop()

        when:
        def router = BoundCompensationRouter.always(compensation)

        then:
        router.routes().isEmpty()
        router.fallback().is(compensation)
        router.select(new RuntimeException(), PATH).is(compensation)
    }

    def 'always() rejects a null compensation'() {
        when:
        BoundCompensationRouter.always(null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'a route matches instances of its exception type'() {
        given:
        def compensation = noop()
        def router = router([route(IllegalStateException, null, compensation)], null)

        expect:
        router.select(new IllegalStateException(), PATH).is(compensation)
    }

    def 'a route matches subclasses of its exception type'() {
        given:
        def compensation = noop()
        def router = router([route(RuntimeException, null, compensation)], null)

        expect:
        router.select(new IllegalArgumentException(), PATH).is(compensation)
    }

    def 'the first matching route wins, in declaration order'() {
        given:
        def first = noop()
        def second = noop()
        def router = router([route(RuntimeException, null, first),
                             route(IllegalStateException, null, second)], null)

        expect: 'the broader route was declared first, so it shadows the narrower one'
        router.select(new IllegalStateException(), PATH).is(first)
    }

    def 'a guard that rejects falls through to the next route'() {
        given:
        def guarded = noop()
        def next = noop()
        def router = router([route(IllegalStateException, { false } as Predicate, guarded),
                             route(RuntimeException, null, next)], null)

        expect:
        router.select(new IllegalStateException(), PATH).is(next)
    }

    def 'a guard that accepts selects its route'() {
        given:
        def compensation = noop()
        def router = router(
            [route(IllegalStateException, { it.message == 'boom' } as Predicate, compensation)], null)

        expect:
        router.select(new IllegalStateException('boom'), PATH).is(compensation)
    }

    def 'a guard that throws is a non-match rather than a second failure'() {
        given:
        def next = noop()
        def router = router(
            [route(IllegalStateException, { throw new RuntimeException('guard') } as Predicate, noop()),
             route(RuntimeException, null, next)], null)

        expect:
        router.select(new IllegalStateException(), PATH).is(next)
    }

    def 'the fallback answers when no route matches'() {
        given:
        def fallback = noop()
        def router = router([route(IllegalStateException, null, noop())], fallback)

        expect:
        router.select(new IllegalArgumentException(), PATH).is(fallback)
    }

    def 'nothing answers when no route matches and there is no fallback'() {
        given:
        def router = router([route(IllegalStateException, null, noop())], null)

        expect:
        router.select(new IllegalArgumentException(), PATH) == null
    }

    def 'the router copies the route list it was handed'() {
        given:
        def mutable = [route(IllegalStateException, null, noop())]
        def router = router(mutable, null)

        when:
        mutable.clear()

        then:
        router.routes().size() == 1
    }

    def 'a route rejects a null exception type or compensation'() {
        when:
        route(exceptionType, null, compensation)

        then:
        thrown(TransfluxValidationException)

        where:
        exceptionType         | compensation
        null                  | { e, c -> } as Compensation
        IllegalStateException | null
    }

    private static Compensation noop() {
        return { e, c -> } as Compensation
    }

    private static BoundCompensationRoute route(Class exceptionType, Predicate guard,
                                                Compensation compensation) {
        return new BoundCompensationRoute(exceptionType, guard, compensation)
    }

    private static BoundCompensationRouter router(List routes, Compensation fallback) {
        return new BoundCompensationRouter(routes, fallback)
    }
}

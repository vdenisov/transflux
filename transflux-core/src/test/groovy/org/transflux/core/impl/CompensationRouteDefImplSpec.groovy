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

import org.transflux.core.action.Action
import org.transflux.core.action.Compensation
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ActionPath
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Predicate

class CompensationRouteDefImplSpec extends Specification {

    static class NoopStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class NoopCompensation implements Compensation<Object, Object> {
        @Override
        void compensate(Object entity, Object context) {
        }
    }

    def 'a route closes back onto the def it was opened on'() {
        given:
        def def_ = openStep()

        when:
        def returned = def_.forException(IllegalStateException).withCompensation(new NoopCompensation())

        then:
        returned.is(def_)
    }

    def 'matching returns the route so the guard and the compensation chain'() {
        given:
        def def_ = openStep()
        def route = def_.forException(IllegalStateException)

        when:
        def returned = route.matching({ true } as Predicate)

        then:
        returned.is(route)
    }

    def 'a bound route carries the declared type, guard and compensation'() {
        given:
        def compensation = new NoopCompensation()
        def def_ = openStep()
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)
        route.matching({ it.message == 'boom' } as Predicate).withCompensation(compensation)

        when:
        def bound = route.buildBound()

        then:
        bound.exceptionType() == IllegalStateException
        bound.compensation().is(compensation)
        bound.matches(new IllegalStateException('boom'), ActionPath.of('s1')) == true
        bound.matches(new IllegalStateException('other'), ActionPath.of('s1')) == false
    }

    def 'a bound route with no guard matches on type alone'() {
        given:
        def def_ = openStep()
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)
        route.withCompensation(new NoopCompensation())

        expect:
        route.buildBound().matches(new IllegalStateException(), ActionPath.of('s1')) == true
    }

    def 'a route reports whether it was closed and whether it carries a guard'() {
        given:
        def def_ = openStep()
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)

        expect:
        route.isClosed() == false
        route.hasGuard() == false

        when:
        route.matching({ true } as Predicate).withCompensation(new NoopCompensation())

        then:
        route.isClosed() == true
        route.hasGuard() == true
    }

    def "a route's label names the owning def and the exception type"() {
        given:
        def def_ = openStep()

        when:
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)

        then:
        route.label().contains("step 's1'")
        route.label().contains(IllegalStateException.name)
    }

    def 'matching twice is last-write-wins'() {
        given:
        def second = { it.message == 'second' } as Predicate
        def def_ = openStep()
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)
        route.matching({ it.message == 'first' } as Predicate)
             .matching(second)
             .withCompensation(new NoopCompensation())

        expect:
        route.buildBound().matches(new IllegalStateException('second'), ActionPath.of('s1')) == true
        route.buildBound().matches(new IllegalStateException('first'), ActionPath.of('s1')) == false
    }

    def 'withCompensation twice on one route is last-write-wins'() {
        given:
        def second = new NoopCompensation()
        def def_ = openStep()
        def route = (CompensationRouteDefImpl) def_.forException(IllegalStateException)
        route.withCompensation(new NoopCompensation()).is(def_)
        route.withCompensation(second)

        expect:
        route.buildBound().compensation().is(second)
    }

    def '#method rejects null'() {
        given:
        def def_ = openStep()
        def route = def_.forException(IllegalStateException)

        when:
        call.call(route)

        then:
        thrown(TransfluxValidationException)

        where:
        method                    | call
        'matching'                | { it.matching((Predicate) null) }
        'withCompensation(inst)'  | { it.withCompensation((Compensation) null) }
    }

    def 'forException rejects a null exception type'() {
        given:
        def def_ = openStep()

        when:
        def_.forException(null)

        then:
        thrown(TransfluxValidationException)
    }

    def "#method on a route held past its owner's configurer throws naming the owner"() {
        given: 'a route opened while the configurer was active, held after it returned'
        def def_ = openStep()
        def route = def_.forException(IllegalStateException)
        def_.endConfigurer()

        when:
        call.call(route)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 's1'")
        e.message.contains('after its configurer has returned')

        where:
        method                    | call
        'matching'                | { it.matching({ true } as Predicate) }
        'withCompensation(inst)'  | { it.withCompensation(new NoopCompensation()) }
    }

    private static StepDefImpl<Object, Object> openStep() {
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep())
        return def_
    }
}

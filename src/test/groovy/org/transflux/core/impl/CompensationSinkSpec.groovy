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
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Predicate

/**
 * The sink's build-time rules. Runtime selection lives on {@code BoundCompensationRouterSpec}, and
 * end-to-end routing on {@code StateMachineImplExceptionRoutingSpec}.
 */
class CompensationSinkSpec extends Specification {

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

    def 'a def that declared nothing builds no table at all'() {
        given:
        def def_ = openStep()

        expect:
        def_.buildBoundAction().compensationRouter() == null
    }

    def 'a bare withCompensation builds a routeless table'() {
        given:
        def compensation = new NoopCompensation()
        def def_ = openStep()
        def_.withCompensation(compensation)

        when:
        def router = def_.buildBoundAction().compensationRouter()

        then:
        router.routes().isEmpty()
        router.fallback().is(compensation)
    }

    def 'routes are bound in declaration order'() {
        given:
        def def_ = openStep()
        def_.forException(IllegalStateException).withCompensation(new NoopCompensation())
            .forException(IllegalArgumentException).withCompensation(new NoopCompensation())

        when:
        def router = def_.buildBoundAction().compensationRouter()

        then:
        router.routes()*.exceptionType() == [IllegalStateException, IllegalArgumentException]
        router.fallback() == null
    }

    def 'routes and a fallback ride on one table'() {
        given:
        def fallback = new NoopCompensation()
        def def_ = openStep()
        def_.forException(IllegalStateException).withCompensation(new NoopCompensation())
            .withCompensation(fallback)

        when:
        def router = def_.buildBoundAction().compensationRouter()

        then:
        router.routes().size() == 1
        router.fallback().is(fallback)
    }

    def 'a route opened and never closed fails the build, naming the owner and the type'() {
        given:
        def def_ = openStep()
        def_.forException(IllegalStateException)

        when:
        def_.buildBoundAction()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 's1'")
        e.message.contains(IllegalStateException.name)
        e.message.contains('declares no compensation')
    }

    def 'an unguarded broader route shadowing a narrower one warns'() {
        given:
        def def_ = openStep()
        def_.forException(RuntimeException).withCompensation(new NoopCompensation())
            .forException(IllegalStateException).withCompensation(new NoopCompensation())

        when:
        def messages = buildCapturingValidation(def_)

        then: 'a warning, not a failure - the definition is still buildable'
        def warning = messages.find { it.contains('Compensation route is unreachable') }
        warning != null
        warning.contains(IllegalStateException.name)
        warning.contains(RuntimeException.name)
    }

    def 'a guarded broader route does not warn: the guard may reject exactly this case'() {
        given:
        def def_ = openStep()
        def_.forException(RuntimeException)
                .matching({ false } as Predicate)
                .withCompensation(new NoopCompensation())
            .forException(IllegalStateException).withCompensation(new NoopCompensation())

        when:
        def messages = buildCapturingValidation(def_)

        then:
        messages.every { !it.contains('Compensation route is unreachable') }
    }

    def 'a narrower route declared first does not warn'() {
        given:
        def def_ = openStep()
        def_.forException(IllegalStateException).withCompensation(new NoopCompensation())
            .forException(RuntimeException).withCompensation(new NoopCompensation())

        when:
        def messages = buildCapturingValidation(def_)

        then:
        messages.every { !it.contains('Compensation route is unreachable') }
    }

    def 'unrelated route types do not warn'() {
        given:
        def def_ = openStep()
        def_.forException(IllegalStateException).withCompensation(new NoopCompensation())
            .forException(IllegalArgumentException).withCompensation(new NoopCompensation())

        when:
        def messages = buildCapturingValidation(def_)

        then:
        messages.every { !it.contains('Compensation route is unreachable') }
    }

    def 'the conditional drives the same sink as the other authoring forms'() {
        given:
        def fallback = new NoopCompensation()
        def cond = new ConditionalOperationDefImpl<Object, Object>('c1')
        cond.beginConfigurer()
        cond.branch('b', { it.condition('b-cond', { e -> true } as Predicate).run('x') })
            .withCompensation(fallback)
            .forException(IllegalStateException).withCompensation(new NoopCompensation())

        when:
        def router = cond.buildBoundAction([:]).compensationRouter()

        then:
        router.routes()*.exceptionType() == [IllegalStateException]
        router.fallback().is(fallback)
    }

    def 'an unclosed route on a conditional fails the build too'() {
        given:
        def cond = new ConditionalOperationDefImpl<Object, Object>('c1')
        cond.beginConfigurer()
        cond.branch('b', { it.condition('b-cond', { e -> true } as Predicate).run('x') })
        cond.forException(IllegalStateException)

        when:
        cond.buildBoundAction([:])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("conditional operation 'c1'")
    }

    private static List<String> buildCapturingValidation(StepDefImpl<Object, Object> def_) {
        def capture = LogCapture.start('org.transflux.build.validation')
        try {
            def_.buildBoundAction()
            return capture.messages()
        } finally {
            capture.stop()
        }
    }

    private static StepDefImpl<Object, Object> openStep() {
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep())
        return def_
    }
}

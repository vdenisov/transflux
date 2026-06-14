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

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Step
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

class StepDefImplSpec extends Specification {

    static class NoopStep implements Step<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    static class CtorlessStep implements Step<Object, Object> {
        CtorlessStep(String arg) {
        }

        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
        }
    }

    def 'constructor rejects null id'() {
        when:
        new StepDefImpl<Object, Object>(null, Object)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step ID cannot be null or blank'
    }

    @Unroll
    def "constructor rejects blank id: '#id'"() {
        when:
        new StepDefImpl<Object, Object>(id, Object)

        then:
        thrown(TransfluxValidationException)

        where:
        id << ['', '  ']
    }

    def 'constructor rejects null context type'() {
        when:
        new StepDefImpl<Object, Object>('s1', null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step context type cannot be null'
    }

    def 'id, contextType, and metadata round-trip'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.withName('My Step').withDescription('does stuff').using(new NoopStep())

        expect:
        def_.getId() == 's1'
        def_.contextType() == Object
        def_.getName() == 'My Step'
        def_.getDescription() == 'does stuff'
    }

    def 'using(instance) resolves to a BoundStep pointing at the instance'() {
        given:
        def step = new NoopStep()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(step)

        when:
        def bound = def_.buildBoundStep()

        then:
        bound.id() == 's1'
        bound.step().is(step)
    }

    def 'using(class) resolves via the no-arg constructor'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(NoopStep)

        expect:
        def_.buildBoundStep().step() instanceof NoopStep
    }

    def 'using(...) twice is last-write-wins'() {
        given:
        def first = new NoopStep()
        def second = new NoopStep()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(first).using(second)

        expect:
        def_.buildBoundStep().step().is(second)
    }

    def 'buildBoundStep without using(...) fails with a clear message'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.buildBoundStep()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("StepDef 's1'")
    }

    def 'buildBoundStep with a class lacking a no-arg constructor fails fast'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(CtorlessStep)

        when:
        def_.buildBoundStep()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }

    @Unroll
    def 'post-configurer #mutator throws naming the step'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.endConfigurer()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 's1'")
        e.message.contains('after its configurer has returned')

        where:
        mutator    | action
        'using'    | { it.using(new NoopStep()) }
        'withName' | { it.withName('n') }
    }
}

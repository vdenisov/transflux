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
import org.transflux.core.action.Action
import org.transflux.core.action.Compensation
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification
import spock.lang.Unroll

class StepDefImplSpec extends Specification {

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

    static class CtorlessCompensation implements Compensation<Object, Object> {
        CtorlessCompensation(String arg) {
        }

        @Override
        void compensate(Object entity, Object context) {
        }
    }

    static class CtorlessStep implements Action<Object, Object> {
        CtorlessStep(String arg) {
        }

        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
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

    def 'using(instance) resolves to a BoundAction pointing at the instance'() {
        given:
        def step = new NoopStep()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(step)

        when:
        def bound = def_.buildBoundAction()

        then:
        bound.id() == 's1'
        bound.action().is(step)
    }

    def 'using(class) resolves via the no-arg constructor'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(NoopStep)

        expect:
        def_.buildBoundAction().action() instanceof NoopStep
    }

    def 'using(...) twice is last-write-wins'() {
        given:
        def first = new NoopStep()
        def second = new NoopStep()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(first).using(second)

        expect:
        def_.buildBoundAction().action().is(second)
    }

    def 'using(class) after using(instance) overrides the instance'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).using(NoopStep)

        expect:
        def_.buildBoundAction().action() instanceof NoopStep
    }

    def 'using(instance) rejects null'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.using((Action<Object, Object>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'using(class) rejects null'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.using((Class<? extends Action<Object, Object>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'buildBoundAction without using(...) fails with a clear message'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.buildBoundAction()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("StepDef 's1'")
    }

    def 'buildBoundAction with a class lacking a no-arg constructor fails fast'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(CtorlessStep)

        when:
        def_.buildBoundAction()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessStep')
    }

    def 'a def that declares no compensation binds none'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep())

        expect:
        def_.buildBoundAction().compensation() == null
    }

    def 'withCompensation(instance) rides onto the bound action'() {
        given:
        def compensation = new NoopCompensation()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).withCompensation(compensation)

        expect:
        def_.buildBoundAction().compensation().is(compensation)
    }

    def 'withCompensation(class) resolves via the no-arg constructor'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).withCompensation(NoopCompensation)

        expect:
        def_.buildBoundAction().compensation() instanceof NoopCompensation
    }

    def 'withCompensation(...) twice is last-write-wins'() {
        given:
        def second = new NoopCompensation()
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).withCompensation(new NoopCompensation()).withCompensation(second)

        expect:
        def_.buildBoundAction().compensation().is(second)
    }

    def 'withCompensation(class) after withCompensation(instance) overrides the instance'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).withCompensation(new NoopCompensation()).withCompensation(NoopCompensation)

        expect:
        def_.buildBoundAction().compensation() instanceof NoopCompensation
    }

    def 'withCompensation(instance) rejects null'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.withCompensation((Compensation<Object, Object>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Compensation cannot be null'
    }

    def 'withCompensation(class) rejects null'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()

        when:
        def_.withCompensation((Class<? extends Compensation<Object, Object>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Compensation class cannot be null'
    }

    def 'a compensation class lacking a no-arg constructor fails at build, not at rollback'() {
        given:
        def def_ = new StepDefImpl<Object, Object>('s1', Object)
        def_.beginConfigurer()
        def_.using(new NoopStep()).withCompensation(CtorlessCompensation)

        when:
        def_.buildBoundAction()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessCompensation')
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
        mutator             | action
        'using'             | { it.using(new NoopStep()) }
        'withName'          | { it.withName('n') }
        'withCompensation'  | { it.withCompensation(new NoopCompensation()) }
    }
}

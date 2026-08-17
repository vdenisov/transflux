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

import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionListener
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class ActionListenerDefImplSpec extends Specification {

    static class NoopListener implements ActionListener<Object, Object> {
        @Override
        void onAction(Object entity, Object context, ActionExecution execution) {
        }
    }

    static class CtorlessListener implements ActionListener<Object, Object> {
        CtorlessListener(String arg) {
        }

        @Override
        void onAction(Object entity, Object context, ActionExecution execution) {
        }
    }

    def 'constructor rejects null id'() {
        when:
        new ActionListenerDefImpl<Object, Object>(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener ID cannot be null or blank'
    }

    @Unroll
    def "constructor rejects blank id: '#id'"() {
        when:
        new ActionListenerDefImpl<Object, Object>(id)

        then:
        thrown(TransfluxValidationException)

        where:
        id << ['', '  ']
    }

    def 'id and metadata round-trip into the bound listener'() {
        given:
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.withName('Audit').withDescription('captures payloads').using(new NoopListener())

        when:
        def bound = def_.buildBoundListener()

        then:
        def_.getId() == 'l1'
        bound.id() == 'l1'
        bound.name() == 'Audit'
        bound.description() == 'captures payloads'
    }

    def 'using(instance) resolves to a BoundActionListener pointing at the instance'() {
        given:
        def listener = new NoopListener()
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.using(listener)

        expect:
        def_.buildBoundListener().listener().is(listener)
    }

    def 'using(class) resolves via the no-arg constructor'() {
        given:
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.using(NoopListener)

        expect:
        def_.buildBoundListener().listener() instanceof NoopListener
    }

    def 'using(...) twice is last-write-wins'() {
        given:
        def first = new NoopListener()
        def second = new NoopListener()
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.using(first).using(second)

        expect:
        def_.buildBoundListener().listener().is(second)
    }

    def 'buildBoundListener without using(...) names the listener and the fix'() {
        given:
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()

        when:
        def_.buildBoundListener()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Action listener 'l1'")
        e.message.contains('declares no listener')
        e.message.contains('using(...)')
    }

    def 'buildBoundListener with a class lacking a no-arg constructor fails fast'() {
        given:
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.using(CtorlessListener)

        when:
        def_.buildBoundListener()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessListener')
    }

    @Unroll
    def 'post-configurer #mutator throws naming the listener'() {
        given:
        def def_ = new ActionListenerDefImpl<Object, Object>('l1')
        def_.beginConfigurer()
        def_.endConfigurer()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("action listener 'l1'")
        e.message.contains('after its configurer has returned')

        where:
        mutator           | action
        'using'           | { it.using(new NoopListener()) }
        'withName'        | { it.withName('n') }
        'withDescription' | { it.withDescription('d') }
    }
}

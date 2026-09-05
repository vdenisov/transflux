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

import org.transflux.core.action.Action
import org.transflux.core.action.ContextMapper
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

/**
 * The member grammar's shared storage and guards, driven directly. The same grammar reached
 * through each owning def is covered by {@code OperationDefImplSpec} and
 * {@code ConditionalOperationDefImplSpec}.
 */
class ActionSequenceSinkSpec extends Specification {

    static class NoopStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class Owner extends ConfigurableDefImpl {
        @Override
        protected String defLabel() {
            return "owner 'o1'"
        }
    }

    def 'members are kept in declaration order, whatever the verb'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when:
        sink.run('a')
        sink.fork('b')
        sink.step('c', new NoopStep(), false)
        sink.conditional('d', { cond -> cond }, false)
        sink.operation('e', { op -> op }, false)
        sink.step('f', new NoopStep(), true)

        then:
        sink.members()*.ref()*.id() == ['a', 'b', 'c', 'd', 'e', 'f']
    }

    def 'only a fork-declared member carries the flag'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when: 'a reference and a declaration of each disposition'
        sink.run('a')
        sink.fork('b')
        sink.step('c', new NoopStep(), false)
        sink.step('d', new NoopStep(), true)
        sink.operation('e', { op -> op }, true)

        then: 'the flag belongs to the call site, whichever verb wrote the member'
        sink.members()*.forked() == [false, true, false, true, true]
    }

    def 'the member view is unmodifiable'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)
        sink.run('a')

        when:
        sink.members().clear()

        then:
        thrown(UnsupportedOperationException)
    }

    def 'a call-site mapper rides on the member reference: #verb'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when:
        sink."$verb"('a', 'mapper-id')
        sink."$verb"('b', { parent -> parent } as ContextMapper)

        then:
        sink.members()*.ref()*.mapperRef()*.getClass()*.simpleName == ['ById', 'InlineMapper']

        where:
        verb << ['run', 'fork']
    }

    def 'every verb is gated by the configurer guard: #verb'() {
        given: 'a def whose configurer has returned'
        def owner = new Owner()

        when:
        call(new ActionSequenceSink<Object, Object, Object>(owner, owner))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains(verb)
        e.message.contains("owner 'o1'")

        where:
        verb              | call
        'run'             | { target -> target.run('a') }
        'fork'            | { target -> target.fork('a') }
        'step'            | { target -> target.step('a', new NoopStep(), false) }
        'conditional'     | { target -> target.conditional('a', { cond -> cond }, false) }
        'operation'       | { target -> target.operation('a', { op -> op }, false) }
        'forkStep'        | { target -> target.step('a', new NoopStep(), true) }
        'forkConditional' | { target -> target.conditional('a', { cond -> cond }, true) }
        'forkOperation'   | { target -> target.operation('a', { op -> op }, true) }
    }

    private static Owner openOwner() {
        def owner = new Owner()
        owner.beginConfigurer()
        return owner
    }
}

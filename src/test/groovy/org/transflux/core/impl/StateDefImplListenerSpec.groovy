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

import org.transflux.core.Identifiable
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateChange
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateListenerDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateDefImplListenerSpec extends Specification {

    static class NoopListener implements StateListener<Object> {
        @Override
        void onState(Object entity, Object context, StateChange<Object> change) {
        }
    }

    @Unroll
    def 'onEntry in the #form form registers an entry listener'() {
        given:
        def s = activeState()

        when:
        declare.call(s)

        then:
        s.getEntryListeners()*.getId() == ['l1']
        s.getExitListeners().isEmpty()
        s.getEntryListeners().first().buildBoundListener().listener() != null

        where:
        form         | declare
        'instance'   | { it.onEntry('l1', new NoopListener()) }
        'class'      | { it.onEntry('l1', NoopListener) }
        'configurer' | { it.onEntry('l1', usingNoop()) }
    }

    @Unroll
    def 'onExit in the #form form registers an exit listener'() {
        given:
        def s = activeState()

        when:
        declare.call(s)

        then:
        s.getExitListeners()*.getId() == ['l1']
        s.getEntryListeners().isEmpty()
        s.getExitListeners().first().buildBoundListener().listener() != null

        where:
        form         | declare
        'instance'   | { it.onExit('l1', new NoopListener()) }
        'class'      | { it.onExit('l1', NoopListener) }
        'configurer' | { it.onExit('l1', usingNoop()) }
    }

    def 'listeners are kept in declaration order'() {
        given:
        def s = activeState()

        when:
        s.onEntry('first', new NoopListener())
         .onEntry('second', new NoopListener())
         .onEntry('third', new NoopListener())

        then:
        s.getEntryListeners()*.getId() == ['first', 'second', 'third']
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def s = activeState()

        when:
        s.onEntry('l1', { StateListenerDef l ->
            l.withName('Audit').withDescription('records entries').using(NoopListener)
        } as Consumer)

        then:
        def bound = s.getEntryListeners().first().buildBoundListener()
        bound.name() == 'Audit'
        bound.description() == 'records entries'
    }

    @Unroll
    def 'the Identifiable overload of #hook in the #form form delegates via getId'() {
        given:
        def s = activeState()

        when:
        declare.call(s)

        then:
        listeners.call(s)*.getId() == ['l1']

        where:
        hook      | form         | declare                                          | listeners
        'onEntry' | 'instance'   | { it.onEntry(idOf('l1'), new NoopListener()) }   | { it.getEntryListeners() }
        'onEntry' | 'class'      | { it.onEntry(idOf('l1'), NoopListener) }         | { it.getEntryListeners() }
        'onEntry' | 'configurer' | { it.onEntry(idOf('l1'), usingNoop()) }          | { it.getEntryListeners() }
        'onExit'  | 'instance'   | { it.onExit(idOf('l1'), new NoopListener()) }    | { it.getExitListeners() }
        'onExit'  | 'class'      | { it.onExit(idOf('l1'), NoopListener) }          | { it.getExitListeners() }
        'onExit'  | 'configurer' | { it.onExit(idOf('l1'), usingNoop()) }           | { it.getExitListeners() }
    }

    def 'a listener id reused on the same hook is rejected'() {
        given:
        def s = activeState()
        s.onEntry('dup', new NoopListener())

        when:
        s.onEntry('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "State listener ID 'dup' is already registered"
    }

    def 'a listener id reused across the two hooks is rejected'() {
        given:
        def s = activeState()
        s.onEntry('dup', new NoopListener())

        when:
        s.onExit('dup', new NoopListener())

        then:
        thrown(TransfluxValidationException)
    }

    def 'a listener id reused across two states is rejected'() {
        given:
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def first = new StateDefImpl<Object>(smd, 'active')
        first.beginConfigurer()
        first.onEntry('dup', new NoopListener())

        and:
        def second = new StateDefImpl<Object>(smd, 'expired')
        second.beginConfigurer()

        when:
        second.onEntry('dup', new NoopListener())

        then:
        thrown(TransfluxValidationException)
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def s = activeState()

        when:
        action.call(s)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State listener ID cannot be null or blank'

        where:
        hook          | action
        'onEntry'     | { it.onEntry('  ', new NoopListener()) }
        'onEntry'     | { it.onEntry('  ', NoopListener) }
        'onEntry-cfg' | { it.onEntry('  ', usingNoop()) }
        'onExit'      | { it.onExit('  ', new NoopListener()) }
        'onExit'      | { it.onExit('  ', NoopListener) }
        'onExit-cfg'  | { it.onExit('  ', usingNoop()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def s = activeState()

        when:
        action.call(s)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State listener identifiable cannot be null'

        where:
        hook      | action
        'onEntry' | { it.onEntry((Identifiable) null, new NoopListener()) }
        'onExit'  | { it.onExit((Identifiable) null, new NoopListener()) }
    }

    @Unroll
    def 'post-configurer #hook throws naming the state'() {
        given:
        def s = activeState()
        s.endConfigurer()

        when:
        action.call(s)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("state 'active'")
        e.message.contains('after its configurer has returned')

        where:
        hook          | action
        'onEntry'     | { it.onEntry('l1', new NoopListener()) }
        'onEntry-cls' | { it.onEntry('l1', NoopListener) }
        'onEntry-cfg' | { it.onEntry('l1', usingNoop()) }
        'onExit'      | { it.onExit('l1', new NoopListener()) }
        'onExit-cls'  | { it.onExit('l1', NoopListener) }
        'onExit-cfg'  | { it.onExit('l1', usingNoop()) }
    }

    private static StateDefImpl<Object> activeState() {
        def smd = Transflux.defineStateMachine() as StateMachineDefImpl
        def s = new StateDefImpl<Object>(smd, 'active')
        s.beginConfigurer()
        return s
    }

    private static Consumer<StateListenerDef<Object>> usingNoop() {
        return { StateListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

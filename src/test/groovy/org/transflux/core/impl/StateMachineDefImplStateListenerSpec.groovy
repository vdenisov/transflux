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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateChange
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateListenerDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateMachineDefImplStateListenerSpec extends Specification {

    static class NoopListener implements StateListener<Object> {
        @Override
        void onState(Object entity, Object context, StateChange<Object> change) {
        }
    }

    @Unroll
    def 'onAnyStateEntry in the #form form registers a global entry listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        smd.getGlobalEntryListeners()*.getId() == ['l1']
        smd.getGlobalExitListeners().isEmpty()
        smd.getGlobalEntryListeners().first().buildBoundListener().listener() != null

        where:
        form                      | declare
        'instance'                | { it.onAnyStateEntry('l1', new NoopListener()) }
        'class'                   | { it.onAnyStateEntry('l1', NoopListener) }
        'configurer'              | { it.onAnyStateEntry('l1', usingNoop()) }
        'Identifiable/instance'   | { it.onAnyStateEntry(idOf('l1'), new NoopListener()) }
        'Identifiable/class'      | { it.onAnyStateEntry(idOf('l1'), NoopListener) }
        'Identifiable/configurer' | { it.onAnyStateEntry(idOf('l1'), usingNoop()) }
    }

    @Unroll
    def 'onAnyStateExit in the #form form registers a global exit listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        smd.getGlobalExitListeners()*.getId() == ['l1']
        smd.getGlobalEntryListeners().isEmpty()
        smd.getGlobalExitListeners().first().buildBoundListener().listener() != null

        where:
        form                      | declare
        'instance'                | { it.onAnyStateExit('l1', new NoopListener()) }
        'class'                   | { it.onAnyStateExit('l1', NoopListener) }
        'configurer'              | { it.onAnyStateExit('l1', usingNoop()) }
        'Identifiable/instance'   | { it.onAnyStateExit(idOf('l1'), new NoopListener()) }
        'Identifiable/class'      | { it.onAnyStateExit(idOf('l1'), NoopListener) }
        'Identifiable/configurer' | { it.onAnyStateExit(idOf('l1'), usingNoop()) }
    }

    def 'global listeners are kept in declaration order'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyStateEntry('first', new NoopListener())
           .onAnyStateEntry('second', new NoopListener())
           .onAnyStateExit('third', new NoopListener())

        then:
        smd.getGlobalEntryListeners()*.getId() == ['first', 'second']
        smd.getGlobalExitListeners()*.getId() == ['third']
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyStateExit('l1', { StateListenerDef l ->
            l.withName('Audit').withDescription('records every exit').using(NoopListener)
        } as Consumer)

        then:
        def bound = smd.getGlobalExitListeners().first().buildBoundListener()
        bound.name() == 'Audit'
        bound.description() == 'records every exit'
    }

    def 'a global listener id reused across the two hooks is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()
        smd.onAnyStateEntry('dup', new NoopListener())

        when:
        smd.onAnyStateExit('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered"
    }

    def 'a configurer that throws leaves the listener id free for a retry'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyStateEntry('audit', { throw new IllegalStateException('bad configurer') } as Consumer)

        then:
        thrown(IllegalStateException)

        when: 'the caller fixes the configurer and retries under the same id'
        smd.onAnyStateEntry('audit', usingNoop())

        then:
        noExceptionThrown()
        smd.getGlobalEntryListeners()*.getId() == ['audit']
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State listener ID cannot be null or blank'

        where:
        hook                    | action
        'onAnyStateEntry'       | { it.onAnyStateEntry('  ', new NoopListener()) }
        'onAnyStateEntry-cls'   | { it.onAnyStateEntry('  ', NoopListener) }
        'onAnyStateEntry-cfg'   | { it.onAnyStateEntry('  ', usingNoop()) }
        'onAnyStateExit'        | { it.onAnyStateExit('  ', new NoopListener()) }
        'onAnyStateExit-cls'    | { it.onAnyStateExit('  ', NoopListener) }
        'onAnyStateExit-cfg'    | { it.onAnyStateExit('  ', usingNoop()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State listener identifiable cannot be null'

        where:
        hook                    | action
        'onAnyStateEntry'       | { it.onAnyStateEntry((Identifiable) null, new NoopListener()) }
        'onAnyStateEntry-cls'   | { it.onAnyStateEntry((Identifiable) null, NoopListener) }
        'onAnyStateEntry-cfg'   | { it.onAnyStateEntry((Identifiable) null, usingNoop()) }
        'onAnyStateExit'        | { it.onAnyStateExit((Identifiable) null, new NoopListener()) }
        'onAnyStateExit-cls'    | { it.onAnyStateExit((Identifiable) null, NoopListener) }
        'onAnyStateExit-cfg'    | { it.onAnyStateExit((Identifiable) null, usingNoop()) }
    }

    def 'a null configurer is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyStateEntry('l1', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'State listener configurer cannot be null'
    }

    private static Consumer<StateListenerDef<Object>> usingNoop() {
        return { StateListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

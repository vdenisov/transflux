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
import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionListener
import org.transflux.core.action.ActionListenerDef
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateMachineDefImplActionListenerSpec extends Specification {

    static class NoopListener implements ActionListener<Object, Object> {
        @Override
        void onAction(Object entity, Object context, ActionExecution<Object> execution) {
        }
    }

    @Unroll
    def 'onAnyActionStart in the #form form registers a global start listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        smd.getGlobalActionStartListeners()*.getId() == ['l1']
        smd.getGlobalActionCompleteListeners().isEmpty()
        smd.getGlobalActionErrorListeners().isEmpty()
        smd.getGlobalActionStartListeners().first().buildBoundListener().listener() != null

        where:
        form                      | declare
        'instance'                | { it.onAnyActionStart('l1', new NoopListener()) }
        'class'                   | { it.onAnyActionStart('l1', NoopListener) }
        'configurer'              | { it.onAnyActionStart('l1', usingNoop()) }
        'Identifiable/instance'   | { it.onAnyActionStart(idOf('l1'), new NoopListener()) }
        'Identifiable/class'      | { it.onAnyActionStart(idOf('l1'), NoopListener) }
        'Identifiable/configurer' | { it.onAnyActionStart(idOf('l1'), usingNoop()) }
    }

    @Unroll
    def 'onAnyActionComplete in the #form form registers a global complete listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        smd.getGlobalActionCompleteListeners()*.getId() == ['l1']
        smd.getGlobalActionStartListeners().isEmpty()
        smd.getGlobalActionErrorListeners().isEmpty()

        where:
        form                      | declare
        'instance'                | { it.onAnyActionComplete('l1', new NoopListener()) }
        'class'                   | { it.onAnyActionComplete('l1', NoopListener) }
        'configurer'              | { it.onAnyActionComplete('l1', usingNoop()) }
        'Identifiable/instance'   | { it.onAnyActionComplete(idOf('l1'), new NoopListener()) }
        'Identifiable/class'      | { it.onAnyActionComplete(idOf('l1'), NoopListener) }
        'Identifiable/configurer' | { it.onAnyActionComplete(idOf('l1'), usingNoop()) }
    }

    @Unroll
    def 'onAnyActionError in the #form form registers a global error listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        smd.getGlobalActionErrorListeners()*.getId() == ['l1']
        smd.getGlobalActionStartListeners().isEmpty()
        smd.getGlobalActionCompleteListeners().isEmpty()

        where:
        form                      | declare
        'instance'                | { it.onAnyActionError('l1', new NoopListener()) }
        'class'                   | { it.onAnyActionError('l1', NoopListener) }
        'configurer'              | { it.onAnyActionError('l1', usingNoop()) }
        'Identifiable/instance'   | { it.onAnyActionError(idOf('l1'), new NoopListener()) }
        'Identifiable/class'      | { it.onAnyActionError(idOf('l1'), NoopListener) }
        'Identifiable/configurer' | { it.onAnyActionError(idOf('l1'), usingNoop()) }
    }

    def 'global action listeners are kept in declaration order'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyActionStart('first', new NoopListener())
           .onAnyActionStart('second', new NoopListener())
           .onAnyActionError('third', new NoopListener())

        then:
        smd.getGlobalActionStartListeners()*.getId() == ['first', 'second']
        smd.getGlobalActionErrorListeners()*.getId() == ['third']
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyActionComplete('l1', { ActionListenerDef l ->
            l.withName('Audit').withDescription('records every action').using(NoopListener)
        } as Consumer)

        then:
        def bound = smd.getGlobalActionCompleteListeners().first().buildBoundListener()
        bound.name() == 'Audit'
        bound.description() == 'records every action'
    }

    def 'an action listener id reused across the three hooks is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()
        smd.onAnyActionStart('dup', new NoopListener())

        when:
        smd.onAnyActionError('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered"
    }

    def 'an action listener id colliding with a state listener id is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()
        smd.onAnyStateEntry('dup', { e, ctx, change -> } as org.transflux.core.state.StateListener)

        when:
        smd.onAnyActionStart('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered"
    }

    def 'a configurer that throws leaves the listener id free for a retry'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyActionStart('audit', { throw new IllegalStateException('bad configurer') } as Consumer)

        then:
        thrown(IllegalStateException)

        when: 'the caller fixes the configurer and retries under the same id'
        smd.onAnyActionStart('audit', usingNoop())

        then:
        noExceptionThrown()
        smd.getGlobalActionStartListeners()*.getId() == ['audit']
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener ID cannot be null or blank'

        where:
        hook                       | action
        'onAnyActionStart'         | { it.onAnyActionStart('  ', new NoopListener()) }
        'onAnyActionStart-cls'     | { it.onAnyActionStart('  ', NoopListener) }
        'onAnyActionStart-cfg'     | { it.onAnyActionStart('  ', usingNoop()) }
        'onAnyActionComplete'      | { it.onAnyActionComplete('  ', new NoopListener()) }
        'onAnyActionComplete-cls'  | { it.onAnyActionComplete('  ', NoopListener) }
        'onAnyActionComplete-cfg'  | { it.onAnyActionComplete('  ', usingNoop()) }
        'onAnyActionError'         | { it.onAnyActionError('  ', new NoopListener()) }
        'onAnyActionError-cls'     | { it.onAnyActionError('  ', NoopListener) }
        'onAnyActionError-cfg'     | { it.onAnyActionError('  ', usingNoop()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener identifiable cannot be null'

        where:
        hook                       | action
        'onAnyActionStart'         | { it.onAnyActionStart((Identifiable) null, new NoopListener()) }
        'onAnyActionStart-cls'     | { it.onAnyActionStart((Identifiable) null, NoopListener) }
        'onAnyActionStart-cfg'     | { it.onAnyActionStart((Identifiable) null, usingNoop()) }
        'onAnyActionComplete'      | { it.onAnyActionComplete((Identifiable) null, new NoopListener()) }
        'onAnyActionComplete-cls'  | { it.onAnyActionComplete((Identifiable) null, NoopListener) }
        'onAnyActionComplete-cfg'  | { it.onAnyActionComplete((Identifiable) null, usingNoop()) }
        'onAnyActionError'         | { it.onAnyActionError((Identifiable) null, new NoopListener()) }
        'onAnyActionError-cls'     | { it.onAnyActionError((Identifiable) null, NoopListener) }
        'onAnyActionError-cfg'     | { it.onAnyActionError((Identifiable) null, usingNoop()) }
    }

    def 'a null configurer is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyActionStart('l1', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener configurer cannot be null'
    }

    private static Consumer<ActionListenerDef<Object, Object>> usingNoop() {
        return { ActionListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

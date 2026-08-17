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
import org.transflux.core.transition.TransitionExecution
import org.transflux.core.transition.TransitionListener
import org.transflux.core.transition.TransitionListenerDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class StateMachineDefImplTransitionListenerSpec extends Specification {

    static class NoopListener implements TransitionListener<Object, Object> {
        @Override
        void onTransition(Object entity, Object context, TransitionExecution<Object> execution) {
        }
    }

    static class NoopStateListener implements StateListener<Object> {
        @Override
        void onState(Object entity, Object context, StateChange<Object> change) {
        }
    }

    @Unroll
    def '#hook in the #form form registers a global listener on that hook'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        listeners.call(smd)*.getId() == ['l1']
        listeners.call(smd).first().buildBoundListener().listener() != null

        and: 'the other two hooks stay empty'
        [smd.getGlobalStartListeners(), smd.getGlobalCompleteListeners(), smd.getGlobalErrorListeners()]*.size().sum() == 1

        where:
        hook                        | form         | declare                                                 | listeners
        'onAnyTransitionStart'      | 'instance'   | { it.onAnyTransitionStart('l1', new NoopListener()) }   | { it.getGlobalStartListeners() }
        'onAnyTransitionStart'      | 'class'      | { it.onAnyTransitionStart('l1', NoopListener) }         | { it.getGlobalStartListeners() }
        'onAnyTransitionStart'      | 'configurer' | { it.onAnyTransitionStart('l1', usingNoop()) }          | { it.getGlobalStartListeners() }
        'onAnyTransitionComplete'   | 'instance'   | { it.onAnyTransitionComplete('l1', new NoopListener()) }| { it.getGlobalCompleteListeners() }
        'onAnyTransitionComplete'   | 'class'      | { it.onAnyTransitionComplete('l1', NoopListener) }      | { it.getGlobalCompleteListeners() }
        'onAnyTransitionComplete'   | 'configurer' | { it.onAnyTransitionComplete('l1', usingNoop()) }       | { it.getGlobalCompleteListeners() }
        'onAnyTransitionError'      | 'instance'   | { it.onAnyTransitionError('l1', new NoopListener()) }   | { it.getGlobalErrorListeners() }
        'onAnyTransitionError'      | 'class'      | { it.onAnyTransitionError('l1', NoopListener) }         | { it.getGlobalErrorListeners() }
        'onAnyTransitionError'      | 'configurer' | { it.onAnyTransitionError('l1', usingNoop()) }          | { it.getGlobalErrorListeners() }
    }

    @Unroll
    def 'the Identifiable overload of #hook in the #form form delegates via getId'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        declare.call(smd)

        then:
        listeners.call(smd)*.getId() == ['l1']

        where:
        hook                      | form         | declare                                                       | listeners
        'onAnyTransitionStart'    | 'instance'   | { it.onAnyTransitionStart(idOf('l1'), new NoopListener()) }   | { it.getGlobalStartListeners() }
        'onAnyTransitionStart'    | 'class'      | { it.onAnyTransitionStart(idOf('l1'), NoopListener) }         | { it.getGlobalStartListeners() }
        'onAnyTransitionStart'    | 'configurer' | { it.onAnyTransitionStart(idOf('l1'), usingNoop()) }          | { it.getGlobalStartListeners() }
        'onAnyTransitionComplete' | 'instance'   | { it.onAnyTransitionComplete(idOf('l1'), new NoopListener()) }| { it.getGlobalCompleteListeners() }
        'onAnyTransitionComplete' | 'class'      | { it.onAnyTransitionComplete(idOf('l1'), NoopListener) }      | { it.getGlobalCompleteListeners() }
        'onAnyTransitionComplete' | 'configurer' | { it.onAnyTransitionComplete(idOf('l1'), usingNoop()) }       | { it.getGlobalCompleteListeners() }
        'onAnyTransitionError'    | 'instance'   | { it.onAnyTransitionError(idOf('l1'), new NoopListener()) }   | { it.getGlobalErrorListeners() }
        'onAnyTransitionError'    | 'class'      | { it.onAnyTransitionError(idOf('l1'), NoopListener) }         | { it.getGlobalErrorListeners() }
        'onAnyTransitionError'    | 'configurer' | { it.onAnyTransitionError(idOf('l1'), usingNoop()) }          | { it.getGlobalErrorListeners() }
    }

    def 'global listeners are kept in declaration order'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyTransitionStart('first', new NoopListener())
           .onAnyTransitionStart('second', new NoopListener())
           .onAnyTransitionError('third', new NoopListener())

        then:
        smd.getGlobalStartListeners()*.getId() == ['first', 'second']
        smd.getGlobalErrorListeners()*.getId() == ['third']
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyTransitionError('l1', { TransitionListenerDef l ->
            l.withName('Audit').withDescription('records every failure').using(NoopListener)
        } as Consumer)

        then:
        def bound = smd.getGlobalErrorListeners().first().buildBoundListener()
        bound.name() == 'Audit'
        bound.description() == 'records every failure'
    }

    def 'a global transition-listener id reused across two hooks is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()
        smd.onAnyTransitionStart('dup', new NoopListener())

        when:
        smd.onAnyTransitionComplete('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered"
    }

    def 'a configurer that throws leaves the listener id free for a retry'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyTransitionStart('audit', { throw new IllegalStateException('bad configurer') } as Consumer)

        then:
        thrown(IllegalStateException)

        when: 'the caller fixes the configurer and retries under the same id'
        smd.onAnyTransitionStart('audit', usingNoop())

        then:
        noExceptionThrown()
        smd.getGlobalStartListeners()*.getId() == ['audit']
    }

    def 'state and transition listeners share one id namespace'() {
        given:
        def smd = new StateMachineDefImpl<Object>()
        smd.onAnyStateEntry('dup', new NoopStateListener())

        when:
        smd.onAnyTransitionStart('dup', new NoopListener())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered"
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition listener ID cannot be null or blank'

        where:
        hook                          | action
        'onAnyTransitionStart'        | { it.onAnyTransitionStart('  ', new NoopListener()) }
        'onAnyTransitionStart-cls'    | { it.onAnyTransitionStart('  ', NoopListener) }
        'onAnyTransitionStart-cfg'    | { it.onAnyTransitionStart('  ', usingNoop()) }
        'onAnyTransitionComplete'     | { it.onAnyTransitionComplete('  ', new NoopListener()) }
        'onAnyTransitionComplete-cls' | { it.onAnyTransitionComplete('  ', NoopListener) }
        'onAnyTransitionComplete-cfg' | { it.onAnyTransitionComplete('  ', usingNoop()) }
        'onAnyTransitionError'        | { it.onAnyTransitionError('  ', new NoopListener()) }
        'onAnyTransitionError-cls'    | { it.onAnyTransitionError('  ', NoopListener) }
        'onAnyTransitionError-cfg'    | { it.onAnyTransitionError('  ', usingNoop()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        action.call(smd)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition listener identifiable cannot be null'

        where:
        hook                          | action
        'onAnyTransitionStart'        | { it.onAnyTransitionStart((Identifiable) null, new NoopListener()) }
        'onAnyTransitionStart-cls'    | { it.onAnyTransitionStart((Identifiable) null, NoopListener) }
        'onAnyTransitionStart-cfg'    | { it.onAnyTransitionStart((Identifiable) null, usingNoop()) }
        'onAnyTransitionComplete'     | { it.onAnyTransitionComplete((Identifiable) null, new NoopListener()) }
        'onAnyTransitionComplete-cls' | { it.onAnyTransitionComplete((Identifiable) null, NoopListener) }
        'onAnyTransitionComplete-cfg' | { it.onAnyTransitionComplete((Identifiable) null, usingNoop()) }
        'onAnyTransitionError'        | { it.onAnyTransitionError((Identifiable) null, new NoopListener()) }
        'onAnyTransitionError-cls'    | { it.onAnyTransitionError((Identifiable) null, NoopListener) }
        'onAnyTransitionError-cfg'    | { it.onAnyTransitionError((Identifiable) null, usingNoop()) }
    }

    def 'a null configurer is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.onAnyTransitionStart('l1', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition listener configurer cannot be null'
    }

    private static Consumer<TransitionListenerDef<Object, Object>> usingNoop() {
        return { TransitionListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

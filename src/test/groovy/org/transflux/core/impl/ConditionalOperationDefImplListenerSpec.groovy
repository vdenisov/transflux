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
import org.transflux.core.action.ActionPhase
import org.transflux.core.action.BranchDef
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

/**
 * A conditional operation's own listener hooks. Its impl extends {@code IdentifiedDefImpl} rather
 * than the sealed {@code ActionDefImpl}, so it carries its own copy of the hook family and needs
 * its own coverage.
 */
class ConditionalOperationDefImplListenerSpec extends Specification {

    static class NoopListener implements ActionListener<Object, Object> {
        @Override
        void onAction(Object entity, Object context, ActionExecution execution) {
        }
    }

    @Unroll
    def '#hook in the #form form registers a listener'() {
        given:
        def def_ = conditional()

        when:
        declare.call(def_)

        then:
        def_.getListeners(phase)*.getId() == ['l1']

        where:
        hook         | form                      | phase                | declare
        'onStart'    | 'instance'                | ActionPhase.START    | { it.onStart('l1', new NoopListener()) }
        'onStart'    | 'class'                   | ActionPhase.START    | { it.onStart('l1', NoopListener) }
        'onStart'    | 'configurer'              | ActionPhase.START    | { it.onStart('l1', usingNoop()) }
        'onStart'    | 'Identifiable/instance'   | ActionPhase.START    | { it.onStart(idOf('l1'), new NoopListener()) }
        'onStart'    | 'Identifiable/class'      | ActionPhase.START    | { it.onStart(idOf('l1'), NoopListener) }
        'onStart'    | 'Identifiable/configurer' | ActionPhase.START    | { it.onStart(idOf('l1'), usingNoop()) }
        'onComplete' | 'instance'                | ActionPhase.COMPLETE | { it.onComplete('l1', new NoopListener()) }
        'onComplete' | 'class'                   | ActionPhase.COMPLETE | { it.onComplete('l1', NoopListener) }
        'onComplete' | 'configurer'              | ActionPhase.COMPLETE | { it.onComplete('l1', usingNoop()) }
        'onComplete' | 'Identifiable/instance'   | ActionPhase.COMPLETE | { it.onComplete(idOf('l1'), new NoopListener()) }
        'onComplete' | 'Identifiable/class'      | ActionPhase.COMPLETE | { it.onComplete(idOf('l1'), NoopListener) }
        'onComplete' | 'Identifiable/configurer' | ActionPhase.COMPLETE | { it.onComplete(idOf('l1'), usingNoop()) }
        'onError'    | 'instance'                | ActionPhase.ERROR    | { it.onError('l1', new NoopListener()) }
        'onError'    | 'class'                   | ActionPhase.ERROR    | { it.onError('l1', NoopListener) }
        'onError'    | 'configurer'              | ActionPhase.ERROR    | { it.onError('l1', usingNoop()) }
        'onError'    | 'Identifiable/instance'   | ActionPhase.ERROR    | { it.onError(idOf('l1'), new NoopListener()) }
        'onError'    | 'Identifiable/class'      | ActionPhase.ERROR    | { it.onError(idOf('l1'), NoopListener) }
        'onError'    | 'Identifiable/configurer' | ActionPhase.ERROR    | { it.onError(idOf('l1'), usingNoop()) }
    }

    def 'the listeners reach the bound action'() {
        given:
        def def_ = conditional()
        def_.onStart('before', new NoopListener())
        def_.branch('only', { BranchDef b ->
            b.condition('always', { e, ctx, tr -> true } as Condition).run('work')
        } as Consumer)

        when:
        def bound = def_.buildBoundAction([:])

        then:
        bound.listeners().onStart()*.id() == ['before']
        bound.listeners().onComplete().isEmpty()
    }

    def 'listeners on one hook keep declaration order'() {
        given:
        def def_ = conditional()

        when:
        def_.onError('first', new NoopListener())
            .onError('second', new NoopListener())

        then:
        def_.getListeners(ActionPhase.ERROR)*.getId() == ['first', 'second']
    }

    @Unroll
    def 'post-configurer #hook throws naming the conditional'() {
        given:
        def def_ = conditional()
        def_.endConfigurer()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("conditional operation 'c1'")
        e.message.contains('after its configurer has returned')

        where:
        hook         | action
        'onStart'    | { it.onStart('l1', new NoopListener()) }
        'onComplete' | { it.onComplete('l1', new NoopListener()) }
        'onError'    | { it.onError('l1', new NoopListener()) }
    }

    private static ConditionalOperationDefImpl<Object, Object> conditional() {
        def def_ = new ConditionalOperationDefImpl<Object, Object>('c1')
        def_.beginConfigurer()
        return def_
    }

    private static Consumer<ActionListenerDef<Object, Object>> usingNoop() {
        return { ActionListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

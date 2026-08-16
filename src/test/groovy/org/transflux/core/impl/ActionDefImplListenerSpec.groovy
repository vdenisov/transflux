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
import org.transflux.core.action.Action
import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionListener
import org.transflux.core.action.ActionListenerDef
import org.transflux.core.action.ActionPhase
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

/**
 * The per-action listener hooks, which {@code StepDefImpl} and {@code OperationDefImpl} both
 * inherit from {@code ActionDefImpl}. The conditional's own copy is covered separately, since its
 * impl deliberately sits outside that base.
 */
class ActionDefImplListenerSpec extends Specification {

    static class NoopListener implements ActionListener<Object, Object> {
        @Override
        void onAction(Object entity, Object context, ActionExecution<Object> execution) {
        }
    }

    @Unroll
    def '#hook in the #form form registers a listener'() {
        given:
        def def_ = step()

        when:
        declare.call(def_)

        then:
        def_.getListeners(phase)*.getId() == ['l1']
        otherPhases(phase).every { def_.getListeners(it).isEmpty() }

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

    def 'a declarative container carries listeners the same way'() {
        given:
        def def_ = new OperationDefImpl<Object, Object>('op')
        def_.beginConfigurer()

        when:
        def_.onStart('before', new NoopListener())
            .onComplete('after', new NoopListener())

        then:
        def_.getListeners(ActionPhase.START)*.getId() == ['before']
        def_.getListeners(ActionPhase.COMPLETE)*.getId() == ['after']
    }

    def 'listeners on one hook keep declaration order'() {
        given:
        def def_ = step()

        when:
        def_.onStart('first', new NoopListener())
            .onStart('second', new NoopListener())
            .onStart('third', new NoopListener())

        then:
        def_.getListeners(ActionPhase.START)*.getId() == ['first', 'second', 'third']
    }

    def 'the listeners reach the bound action in declaration order'() {
        given:
        def def_ = step()
        def_.using({ e, ctx, tr -> } as Action)
        def_.onStart('s1', new NoopListener())
            .onStart('s2', new NoopListener())
            .onError('e1', new NoopListener())

        when:
        def bound = def_.buildBoundAction()

        then:
        bound.listeners().onStart()*.id() == ['s1', 's2']
        bound.listeners().onComplete().isEmpty()
        bound.listeners().onError()*.id() == ['e1']
    }

    def 'an action declaring no listeners builds an empty listener set'() {
        given:
        def def_ = step()
        def_.using({ e, ctx, tr -> } as Action)

        expect:
        def_.buildBoundAction().listeners().isEmpty()
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def def_ = step()
        def_.using({ e, ctx, tr -> } as Action)

        when:
        def_.onError('l1', { ActionListenerDef l ->
            l.withName('Audit').withDescription('captures the failure').using(NoopListener)
        } as Consumer)

        then:
        def bound = def_.buildBoundAction().listeners().onError().first()
        bound.name() == 'Audit'
        bound.description() == 'captures the failure'
    }

    @Unroll
    def 'post-configurer #hook throws naming the def'() {
        given:
        def def_ = step()
        def_.endConfigurer()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("step 's1'")
        e.message.contains('after its configurer has returned')

        where:
        hook         | action
        'onStart'    | { it.onStart('l1', new NoopListener()) }
        'onComplete' | { it.onComplete('l1', new NoopListener()) }
        'onError'    | { it.onError('l1', new NoopListener()) }
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def def_ = step()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener ID cannot be null or blank'

        where:
        hook             | action
        'onStart'        | { it.onStart('  ', new NoopListener()) }
        'onStart-cls'    | { it.onStart('  ', NoopListener) }
        'onStart-cfg'    | { it.onStart('  ', usingNoop()) }
        'onComplete'     | { it.onComplete('  ', new NoopListener()) }
        'onError'        | { it.onError('  ', new NoopListener()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def def_ = step()

        when:
        action.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener identifiable cannot be null'

        where:
        hook             | action
        'onStart'        | { it.onStart((Identifiable) null, new NoopListener()) }
        'onComplete'     | { it.onComplete((Identifiable) null, NoopListener) }
        'onError'        | { it.onError((Identifiable) null, usingNoop()) }
    }

    def 'a null listener is rejected'() {
        given:
        def def_ = step()

        when:
        def_.onStart('l1', (ActionListener) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener cannot be null'
    }

    def 'a null configurer is rejected'() {
        given:
        def def_ = step()

        when:
        def_.onStart('l1', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Action listener configurer cannot be null'
    }

    private static StepDefImpl<Object, Object> step() {
        def def_ = new StepDefImpl<Object, Object>('s1')
        def_.beginConfigurer()
        return def_
    }

    private static List<ActionPhase> otherPhases(ActionPhase phase) {
        return ActionPhase.values().findAll { it != phase }
    }

    private static Consumer<ActionListenerDef<Object, Object>> usingNoop() {
        return { ActionListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

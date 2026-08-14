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
import org.transflux.core.transition.TransitionExecution
import org.transflux.core.transition.TransitionListener
import org.transflux.core.transition.TransitionListenerDef
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

class TransitionDefImplListenerSpec extends Specification {

    static class NoopListener implements TransitionListener<Object, Object> {
        @Override
        void onTransition(Object entity, Object context, TransitionExecution<Object, Object> execution) {
        }
    }

    @Unroll
    def '#hook in the #form form registers a listener on that hook'() {
        given:
        def td = transition()

        when:
        declare.call(td)

        then:
        listeners.call(td)*.getId() == ['l1']
        listeners.call(td).first().buildBoundListener().listener() != null

        and: 'the other two hooks stay empty'
        td.getStartListeners().size() + td.getCompleteListeners().size() + td.getErrorListeners().size() == 1

        where:
        hook         | form         | declare                                        | listeners
        'onStart'    | 'instance'   | { it.onStart('l1', new NoopListener()) }       | { it.getStartListeners() }
        'onStart'    | 'class'      | { it.onStart('l1', NoopListener) }             | { it.getStartListeners() }
        'onStart'    | 'configurer' | { it.onStart('l1', usingNoop()) }              | { it.getStartListeners() }
        'onComplete' | 'instance'   | { it.onComplete('l1', new NoopListener()) }    | { it.getCompleteListeners() }
        'onComplete' | 'class'      | { it.onComplete('l1', NoopListener) }          | { it.getCompleteListeners() }
        'onComplete' | 'configurer' | { it.onComplete('l1', usingNoop()) }           | { it.getCompleteListeners() }
        'onError'    | 'instance'   | { it.onError('l1', new NoopListener()) }       | { it.getErrorListeners() }
        'onError'    | 'class'      | { it.onError('l1', NoopListener) }             | { it.getErrorListeners() }
        'onError'    | 'configurer' | { it.onError('l1', usingNoop()) }              | { it.getErrorListeners() }
    }

    @Unroll
    def 'the Identifiable overload of #hook in the #form form delegates via getId'() {
        given:
        def td = transition()

        when:
        declare.call(td)

        then:
        listeners.call(td)*.getId() == ['l1']

        where:
        hook         | form         | declare                                             | listeners
        'onStart'    | 'instance'   | { it.onStart(idOf('l1'), new NoopListener()) }      | { it.getStartListeners() }
        'onStart'    | 'class'      | { it.onStart(idOf('l1'), NoopListener) }            | { it.getStartListeners() }
        'onStart'    | 'configurer' | { it.onStart(idOf('l1'), usingNoop()) }             | { it.getStartListeners() }
        'onComplete' | 'instance'   | { it.onComplete(idOf('l1'), new NoopListener()) }   | { it.getCompleteListeners() }
        'onComplete' | 'class'      | { it.onComplete(idOf('l1'), NoopListener) }         | { it.getCompleteListeners() }
        'onComplete' | 'configurer' | { it.onComplete(idOf('l1'), usingNoop()) }          | { it.getCompleteListeners() }
        'onError'    | 'instance'   | { it.onError(idOf('l1'), new NoopListener()) }      | { it.getErrorListeners() }
        'onError'    | 'class'      | { it.onError(idOf('l1'), NoopListener) }            | { it.getErrorListeners() }
        'onError'    | 'configurer' | { it.onError(idOf('l1'), usingNoop()) }             | { it.getErrorListeners() }
    }

    def 'listeners are kept in declaration order'() {
        given:
        def td = transition()

        when:
        td.onStart('first', new NoopListener())
          .onStart('second', new NoopListener())
          .onStart('third', new NoopListener())

        then:
        td.getStartListeners()*.getId() == ['first', 'second', 'third']
    }

    def 'the configurer form carries metadata through to the bound listener'() {
        given:
        def td = transition()

        when:
        td.onComplete('l1', { TransitionListenerDef l ->
            l.withName('Audit').withDescription('records completions').using(NoopListener)
        } as Consumer)

        then:
        def bound = td.getCompleteListeners().first().buildBoundListener()
        bound.name() == 'Audit'
        bound.description() == 'records completions'
    }

    @Unroll
    def '#hook rejects a blank listener id'() {
        given:
        def td = transition()

        when:
        action.call(td)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition listener ID cannot be null or blank'

        where:
        hook             | action
        'onStart'        | { it.onStart('  ', new NoopListener()) }
        'onStart-cls'    | { it.onStart('  ', NoopListener) }
        'onStart-cfg'    | { it.onStart('  ', usingNoop()) }
        'onComplete'     | { it.onComplete('  ', new NoopListener()) }
        'onComplete-cls' | { it.onComplete('  ', NoopListener) }
        'onComplete-cfg' | { it.onComplete('  ', usingNoop()) }
        'onError'        | { it.onError('  ', new NoopListener()) }
        'onError-cls'    | { it.onError('  ', NoopListener) }
        'onError-cfg'    | { it.onError('  ', usingNoop()) }
    }

    @Unroll
    def '#hook rejects a null Identifiable'() {
        given:
        def td = transition()

        when:
        action.call(td)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Transition listener identifiable cannot be null'

        where:
        hook             | action
        'onStart'        | { it.onStart((Identifiable) null, new NoopListener()) }
        'onStart-cls'    | { it.onStart((Identifiable) null, NoopListener) }
        'onStart-cfg'    | { it.onStart((Identifiable) null, usingNoop()) }
        'onComplete'     | { it.onComplete((Identifiable) null, new NoopListener()) }
        'onComplete-cls' | { it.onComplete((Identifiable) null, NoopListener) }
        'onComplete-cfg' | { it.onComplete((Identifiable) null, usingNoop()) }
        'onError'        | { it.onError((Identifiable) null, new NoopListener()) }
        'onError-cls'    | { it.onError((Identifiable) null, NoopListener) }
        'onError-cfg'    | { it.onError((Identifiable) null, usingNoop()) }
    }

    @Unroll
    def 'post-configurer #hook throws naming the transition'() {
        given:
        def td = transition()
        td.endConfigurer()

        when:
        action.call(td)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("transition 't1'")
        e.message.contains('after its configurer has returned')

        where:
        hook             | action
        'onStart'        | { it.onStart('l1', new NoopListener()) }
        'onStart-cls'    | { it.onStart('l1', NoopListener) }
        'onStart-cfg'    | { it.onStart('l1', usingNoop()) }
        'onComplete'     | { it.onComplete('l1', new NoopListener()) }
        'onComplete-cls' | { it.onComplete('l1', NoopListener) }
        'onComplete-cfg' | { it.onComplete('l1', usingNoop()) }
        'onError'        | { it.onError('l1', new NoopListener()) }
        'onError-cls'    | { it.onError('l1', NoopListener) }
        'onError-cfg'    | { it.onError('l1', usingNoop()) }
    }

    private static TransitionDefImpl<Object, Object> transition() {
        def td = new TransitionDefImpl<Object, Object>('t1', 's1', 's2')
        td.beginConfigurer()
        return td
    }

    private static Consumer<TransitionListenerDef<Object, Object>> usingNoop() {
        return { TransitionListenerDef l -> l.using(NoopListener) } as Consumer
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }
}

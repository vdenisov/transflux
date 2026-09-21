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

import ch.qos.logback.classic.Level
import org.transflux.core.action.ActionListener
import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateListener
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification

/**
 * The async declaration all three listener defs share, and that it reaches each bound listener.
 */
class ListenerDefImplSpec extends Specification {

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def '#kind listener is synchronous unless declared otherwise'() {
        given:
        def listenerDef = configured(factory)

        expect:
        listenerDef.getAsync() == null
        listenerDef.buildBoundListener().async() == null

        where:
        kind         | factory
        'state'      | { stateListener() }
        'transition' | { transitionListener() }
        'action'     | { actionListener() }
    }

    def 'withAsync() on a #kind listener defaults to DROP and reaches the bound listener'() {
        given:
        def listenerDef = configured(factory)

        when:
        listenerDef.withAsync()

        then:
        listenerDef.buildBoundListener().async() == AsyncRejectionPolicy.DROP

        where:
        kind         | factory
        'state'      | { stateListener() }
        'transition' | { transitionListener() }
        'action'     | { actionListener() }
    }

    def 'withAsync(#policy) is carried as declared'() {
        given:
        def listenerDef = configured { stateListener() }

        when:
        listenerDef.withAsync(policy)

        then:
        listenerDef.buildBoundListener().async() == policy

        where:
        policy << [AsyncRejectionPolicy.DROP, AsyncRejectionPolicy.BLOCK, AsyncRejectionPolicy.CALLER_RUNS]
    }

    def 'withAsync(FAIL) is refused naming the listener'() {
        given:
        def listenerDef = configured { transitionListener() }

        when:
        listenerDef.withAsync(AsyncRejectionPolicy.FAIL)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('FAIL')
        e.message.contains("transition listener 'l1'")
        listenerDef.getAsync() == null
    }

    def 'withAsync(null) is refused'() {
        given:
        def listenerDef = configured { actionListener() }

        when:
        listenerDef.withAsync(null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'declaring async twice is last-write-wins with a warning'() {
        given:
        def listenerDef = configured { stateListener() }
        capture = LogCapture.start('org.transflux.build.validation')

        when:
        listenerDef.withAsync().withAsync(AsyncRejectionPolicy.CALLER_RUNS)

        then:
        listenerDef.getAsync() == AsyncRejectionPolicy.CALLER_RUNS
        capture.messagesAtOrAbove(Level.WARN).size() == 1
    }

    def 'withAsync after the configurer returned throws naming the listener'() {
        given:
        def listenerDef = configured { actionListener() }
        listenerDef.endConfigurer()

        when:
        listenerDef.withAsync()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("action listener 'l1'")
        e.message.contains('after its configurer has returned')
    }

    private static ListenerDefImpl configured(Closure<ListenerDefImpl> factory) {
        def listenerDef = factory.call()
        listenerDef.beginConfigurer()
        return listenerDef
    }

    private static StateListenerDefImpl stateListener() {
        def listenerDef = new StateListenerDefImpl<Object>('l1')
        listenerDef.beginConfigurer()
        listenerDef.using({ e, c, change -> } as StateListener)
        listenerDef.endConfigurer()
        return listenerDef
    }

    private static TransitionListenerDefImpl transitionListener() {
        def listenerDef = new TransitionListenerDefImpl<Object, Object>('l1')
        listenerDef.beginConfigurer()
        listenerDef.using({ e, c, execution -> } as TransitionListener)
        listenerDef.endConfigurer()
        return listenerDef
    }

    private static ActionListenerDefImpl actionListener() {
        def listenerDef = new ActionListenerDefImpl<Object, Object>('l1')
        listenerDef.beginConfigurer()
        listenerDef.using({ e, c, execution -> } as ActionListener)
        listenerDef.endConfigurer()
        return listenerDef
    }
}

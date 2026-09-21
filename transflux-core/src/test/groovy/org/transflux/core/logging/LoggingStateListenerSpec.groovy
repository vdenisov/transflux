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

package org.transflux.core.logging

import org.slf4j.event.Level
import org.transflux.core.impl.LogCapture
import spock.lang.Specification

import static org.transflux.core.logging.TraceFixture.Order
import static org.transflux.core.logging.TraceFixture.machine

class LoggingStateListenerSpec extends Specification {

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'exit and entry each write one line naming the state and the transition'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).stateListener()
        def sm = machine({ smd -> smd.onAnyStateExit('out', listener).onAnyStateEntry('in', listener) })
        capture = LogCapture.start('org.transflux.trace.state')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages() == [
            'State exited, stateId=s1, transitionId=t',
            'State entered, stateId=s2, transitionId=t'
        ]
    }
}

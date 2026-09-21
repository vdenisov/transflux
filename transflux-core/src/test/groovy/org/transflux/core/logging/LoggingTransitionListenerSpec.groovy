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
import org.transflux.core.action.Action
import org.transflux.core.impl.LogCapture
import spock.lang.Specification

import static org.transflux.core.logging.TraceFixture.Order
import static org.transflux.core.logging.TraceFixture.machine

class LoggingTransitionListenerSpec extends Specification {

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'a completed transition writes its start and its executed path'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).transitionListener()
        def sm = machine({ smd ->
            smd.step('work', { o, c, t -> } as Action)
               .onAnyTransitionStart('start', listener)
               .onAnyTransitionComplete('done', listener)
        }, { t -> t.run('work') })
        capture = LogCapture.start('org.transflux.trace.transition')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages() == [
            'Transition started, transitionId=t, source=s1, target=s2, firedBy=null',
            'Transition completed, transitionId=t, executedPath=[work]'
        ]
    }

    def 'a failed transition writes the error type, never its message, and the compensated path'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).withTimings().transitionListener()
        def sm = machine({ smd ->
            smd.step('work', { o, c, t -> throw new IllegalStateException('SECRET-MESSAGE') } as Action)
               .onAnyTransitionError('failed', listener)
        }, { t -> t.run('work') })
        capture = LogCapture.start('org.transflux.trace.transition')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        def line = capture.messages().first()
        line =~ /^Transition failed, transitionId=t, errorType=java\.lang\.IllegalStateException, compensatedPath=\[\], durationMs=\d+$/
        !line.contains('SECRET-MESSAGE')
    }
}

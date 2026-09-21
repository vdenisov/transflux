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

class LoggingActionListenerSpec extends Specification {

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'an action writes its start with its kind, and its completion with its duration'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).withTimings().actionListener()
        def sm = machine({ smd ->
            smd.step('work', { o, c, t -> } as Action)
               .onAnyActionStart('start', listener)
               .onAnyActionComplete('done', listener)
        }, { t -> t.run('work') })
        capture = LogCapture.start('org.transflux.trace.action')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages()[0] == 'Action started, path=work, kind=STEP'
        capture.messages()[1] =~ /^Action completed, path=work, durationMs=\d+$/
    }

    def 'a nested action is written under its qualified path'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).actionListener()
        def sm = machine({ smd ->
            smd.step('inner', { o, c, t -> } as Action)
               .step('outer', { o, c, view -> view.run('inner') } as Action)
               .onAnyActionComplete('done', listener)
        }, { t -> t.run('outer') })
        capture = LogCapture.start('org.transflux.trace.action')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages() == ['Action completed, path=outer/inner', 'Action completed, path=outer']
    }

    def 'a failed action writes the error type, never its message'() {
        given:
        def listener = ExecutionLogging.atLevel(Level.INFO).actionListener()
        def sm = machine({ smd ->
            smd.step('work', { o, c, t -> throw new IllegalStateException('SECRET-MESSAGE') } as Action)
               .onAnyActionError('failed', listener)
        }, { t -> t.run('work') })
        capture = LogCapture.start('org.transflux.trace.action')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages() == ['Action failed, path=work, errorType=java.lang.IllegalStateException']
    }
}

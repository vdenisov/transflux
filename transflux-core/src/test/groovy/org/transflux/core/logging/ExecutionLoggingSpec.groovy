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

import ch.qos.logback.classic.Level as LogbackLevel
import org.slf4j.event.Level
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.impl.LogCapture
import org.transflux.core.state.StateListener
import spock.lang.Specification

import static org.transflux.core.logging.TraceFixture.Order
import static org.transflux.core.logging.TraceFixture.Payload
import static org.transflux.core.logging.TraceFixture.machine

/**
 * The options value: its defaults, that every setter copies, and what each option adds to a line.
 */
class ExecutionLoggingSpec extends Specification {

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'defaults log at DEBUG with nothing optional'() {
        when:
        def logging = ExecutionLogging.defaults()

        then:
        logging.level() == Level.DEBUG
        !logging.logsContext()
        !logging.logsTimings()
        logging.labelOf(new Order('o-1', 's1')) == null
    }

    def 'every with... returns a copy and leaves the original untouched'() {
        given:
        def original = ExecutionLogging.atLevel(Level.INFO)

        when:
        def changed = original.withContext().withTimings().withEntityLabel({ Order o -> o.id })

        then:
        !original.logsContext()
        !original.logsTimings()
        original.labelOf(new Order('o-1', 's1')) == null
        changed.level() == Level.INFO
        changed.logsContext()
        changed.logsTimings()
        changed.labelOf(new Order('o-1', 's1')) == 'o-1'
    }

    def 'a null #argument is refused'() {
        when:
        action.call()

        then:
        thrown(TransfluxValidationException)

        where:
        argument       | action
        'level'        | { ExecutionLogging.atLevel(null) }
        'entity label' | { ExecutionLogging.defaults().withEntityLabel(null) }
    }

    def 'the factories hand out listeners of each category'() {
        given:
        def logging = ExecutionLogging.defaults()

        expect:
        logging.stateListener() instanceof StateListener
        logging.transitionListener() != null
        logging.actionListener() != null
    }

    def 'a line carries no context, entity or duration unless asked for'() {
        given:
        def sm = machine({ smd ->
            smd.onAnyTransitionComplete('log', ExecutionLogging.atLevel(Level.INFO).transitionListener())
        })
        capture = LogCapture.start('org.transflux.trace')

        when:
        sm.entity(new Order('o-1', 's1')).transitionTo('s2', new Payload())

        then:
        def line = capture.messages().find { it.startsWith('Transition completed') }
        line != null
        !line.contains('CONTEXT-PAYLOAD')
        !line.contains('ORDER-PAYLOAD')
        !line.contains('entity=')
        !line.contains('durationMs=')
    }

    def 'the options append the entity label, the duration and the context, in that order'() {
        given:
        def logging = ExecutionLogging.atLevel(Level.INFO)
            .withEntityLabel({ Order o -> o.id })
            .withTimings()
            .withContext()
        def sm = machine({ smd -> smd.onAnyTransitionComplete('log', logging.transitionListener()) })
        capture = LogCapture.start('org.transflux.trace')

        when:
        sm.entity(new Order('o-1', 's1')).transitionTo('s2', new Payload())

        then:
        def line = capture.messages().find { it.startsWith('Transition completed') }
        line =~ /, entity=o-1, durationMs=\d+, context=CONTEXT-PAYLOAD$/
        !line.contains('ORDER-PAYLOAD')
    }

    def 'lines go out at the configured level'() {
        given:
        def sm = machine({ smd ->
            smd.onAnyStateEntry('log', ExecutionLogging.atLevel(Level.WARN).stateListener())
        })
        capture = LogCapture.start('org.transflux.trace')

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.events()*.level*.toString() == ['WARN']
    }

    def 'a disabled level builds nothing, the entity label included'() {
        given:
        def labelled = []
        def logging = ExecutionLogging.atLevel(Level.DEBUG).withEntityLabel({ Order o -> labelled << o; o.id })
        def sm = machine({ smd -> smd.onAnyStateEntry('log', logging.stateListener()) })
        capture = LogCapture.start('org.transflux.trace', LogbackLevel.INFO)

        when:
        sm.executeTransition(new Order('o-1', 's1'), 's2')

        then:
        capture.messages().isEmpty()
        labelled.isEmpty()
    }
}

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

import org.slf4j.event.Level
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.logging.ExecutionLogging
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer

/**
 * The one-call registration: what it attaches, under which ids, and the trace a transition leaves.
 */
class StateMachineDefImplExecutionLoggingSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'a transition leaves the whole trace, in execution order, on the trace subtree'() {
        given:
        def smd = definition()
        smd.withExecutionLogging(ExecutionLogging.atLevel(Level.INFO))
        def sm = smd.build()
        capture = LogCapture.start('org.transflux.trace')

        when:
        sm.executeTransition(new Entity('s1'), 's2')

        then:
        capture.messages() == [
            'Transition started, transitionId=t, source=s1, target=s2, firedBy=null',
            'State exited, stateId=s1, transitionId=t',
            'Action started, path=work, kind=STEP',
            'Action completed, path=work',
            'Transition completed, transitionId=t, executedPath=[work]',
            'State entered, stateId=s2, transitionId=t'
        ]
    }

    def 'withExecutionLogging() logs at DEBUG'() {
        given:
        def smd = definition()
        smd.withExecutionLogging()
        def sm = smd.build()
        capture = LogCapture.start('org.transflux.trace')

        when:
        sm.executeTransition(new Entity('s1'), 's2')

        then:
        !capture.events().isEmpty()
        capture.events().every { it.level.toString() == 'DEBUG' }
    }

    def 'declaring it twice fails on the listener ids'() {
        given:
        def smd = definition()
        smd.withExecutionLogging()

        when:
        smd.withExecutionLogging()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('transflux-log-state-entry')
    }

    def 'its ids are taken, so a host listener cannot reuse one'() {
        given:
        def smd = definition()
        smd.withExecutionLogging()

        when:
        smd.onAnyStateEntry(id, { e, c, change -> } as StateListener)

        then:
        thrown(TransfluxValidationException)

        where:
        id << ['transflux-log-state-entry', 'transflux-log-state-exit']
    }

    def 'null options are refused'() {
        when:
        definition().withExecutionLogging(null)

        then:
        thrown(TransfluxValidationException)
    }

    private static StateMachineDefImpl<Entity> definition() {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .step('work', { e, c, t -> } as Action)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> t.run('work') } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd
    }
}

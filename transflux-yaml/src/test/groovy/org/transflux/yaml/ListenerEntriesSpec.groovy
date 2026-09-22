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

package org.transflux.yaml

import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import static org.transflux.yaml.LoaderFixtures.*

class ListenerEntriesSpec extends Specification {

    def setup() {
        TRAIL.clear()
    }

    def 'a registered listener takes the category of the interface its class implements'() {
        given:
        def definition = load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
            listeners:
              - id: states
                class: ${StateAudit.name}
              - id: transitions
                class: ${TransitionAudit.name}
              - id: actions
                class: ${ActionAudit.name}
            """)
            .onAnyStateEntry('states')
            .onAnyTransitionComplete('transitions')
            .onAnyActionComplete('actions')
        def sm = machine(definition) { it.run('record') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['step', 'action:COMPLETE:record', 'transition:COMPLETE', 'state:ENTRY:b']

        cleanup:
        sm?.close()
    }

    def 'a class implementing several categories is registered under the one its type names'() {
        given:
        def definition = load("""\
            listeners:
              - id: every
                class: ${EveryAudit.name}
                type: transition
            """)
        def sm = machine(definition) { it.onComplete('every') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['every-transition:COMPLETE']

        cleanup:
        sm?.close()
    }

    def 'a listener registered against a context attaches to a transition carrying it'() {
        given:
        def definition = load("""\
            listeners:
              - id: typed
                name: Typed
                description: Sees the context
                class: ${CtxTransitionAudit.name}
                context: ${Ctx.name}
            """)
        def sm = machine(definition, Ctx) { it.onStart('typed') }

        when:
        sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        TRAIL == ['ctx-transition:START']

        cleanup:
        sm?.close()
    }

    def 'an async listener is notified off the transition thread'() {
        given:
        def definition = load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
            listeners:
              - id: later
                class: ${ThreadAudit.name}
                async: true
                onRejection: CALLER_RUNS
            """).onAnyActionComplete('later')
        def sm = machine(definition) { it.run('record') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        new PollingConditions(timeout: 5).eventually {
            assert TRAIL.size() == 2
        }
        TRAIL[1].startsWith('thread:COMPLETE:')
        TRAIL[1] != "thread:COMPLETE:${Thread.currentThread().name}".toString()

        cleanup:
        sm?.close()
    }

    def 'refuses #what'() {
        when:
        load(sections)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        what                                | sections                                                                                   || message
        'a class that is no listener'       | "listeners:\n  - id: l\n    class: ${RecordingStep.name}\n"                               || "root.yml:6:12: listener 'l': class ${RecordingStep.name} is not a StateListener, TransitionListener or ActionListener"
        'an ambiguous class'                | "listeners:\n  - id: l\n    class: ${EveryAudit.name}\n"                                  || "root.yml:6:12: listener 'l': class ${EveryAudit.name} implements the state, transition and action listener interfaces; say which this registration is with 'type'"
        'an unknown type'                   | "listeners:\n  - id: l\n    class: ${EveryAudit.name}\n    type: trigger\n"               || "root.yml:7:11: listener 'l': 'type' must be one of state, transition, action, not 'trigger'"
        'a type the class does not have'    | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n    type: action\n"                || "root.yml:7:11: listener 'l': class ${StateAudit.name} is not a org.transflux.core.action.ActionListener"
        'a state listener with a context'   | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n    context: ${Ctx.name}\n"        || "root.yml:7:14: listener 'l': a state listener takes no context: it is handed whichever context the transition carries"
        'another context argument'          | "listeners:\n  - id: l\n    class: ${CtxTransitionAudit.name}\n    context: ${ChildCtx.name}\n" || "root.yml:6:12: listener 'l': class ${CtxTransitionAudit.name} declares TransitionListener's C as ${Ctx.name}, where this position needs ${ChildCtx.name}"
        'a typed listener with no context'  | "listeners:\n  - id: l\n    class: ${CtxTransitionAudit.name}\n"                          ||"root.yml:6:12: listener 'l': class ${CtxTransitionAudit.name} declares TransitionListener's C as ${Ctx.name}, where this position needs java.lang.Object"
        'onRejection without async'         | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n    onRejection: DROP\n"          || "root.yml:7:18: listener 'l': 'onRejection' applies to an async listener; add 'async: true'"
        'FAIL'                              | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n    async: true\n    onRejection: FAIL\n" || "root.yml:8:18: listener 'l': Async rejection policy FAIL is not available on state listener 'l'; a listener cannot fail the transition it observes, so choose DROP, BLOCK or CALLER_RUNS"
        'an async that is not a boolean'    | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n    async: yes\n"                 || "root.yml:7:12: listener 'l': 'async' must be true or false, not 'yes'"
        'an id claimed twice'               | "listeners:\n  - id: l\n    class: ${StateAudit.name}\n  - id: l\n    class: ${StateAudit.name}\n" || "root.yml:7:9: listener 'l': Listener ID 'l' is already registered"
    }
}

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

import static org.transflux.yaml.LoaderFixtures.*

class ActionKeysSpec extends Specification {

    def setup() {
        TRAIL.clear()
    }

    def 'an action compensation runs when the transition fails'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
                compensation: ${Undo.name}
              - id: fail
                class: ${FailingStep.name}
            """)) { it.run('record').run('fail') }

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        !result.success
        TRAIL == ['step', 'undo']

        cleanup:
        sm?.close()
    }

    def 'a route answers for its failure before the fallback, #form guard'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
                compensation: ${Undo.name}
                errorHandling:
                  - exception: ${IllegalStateException.name}
                    guard:
                      ${guard}
                    compensation: ${RouteUndo.name}
              - id: fail
                class: ${FailingStep.name}
            """)) { it.run('record').run('fail') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['step', expected]

        cleanup:
        sm?.close()

        where:
        form                  | guard                                    || expected
        'a matching class'    | "class: ${BoomGuard.name}"               || 'route-undo'
        'a matching expr'     | "expression: \"message == 'boom'\""      || 'route-undo'
        'a missing expr'      | "expression: \"message == 'other'\""     || 'undo'
    }

    def 'an unguarded route answers for every failure of its type'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
                errorHandling:
                  - exception: ${RuntimeException.name}
                    compensation: ${RouteUndo.name}
              - id: fail
                class: ${FailingStep.name}
            """)) { it.run('record').run('fail') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['step', 'route-undo']

        cleanup:
        sm?.close()
    }

    def 'listeners declared on a step fire at each of its hooks, referenced or declared in place'() {
        given:
        def sm = machine(load("""\
            listeners:
              - id: shared
                class: ${ActionAudit.name}
            steps:
              - id: record
                class: ${RecordingStep.name}
                listeners:
                  onStart:
                    - shared
                    - id: own
                      name: Own
                      class: ${EveryAudit.name}
                  onComplete:
                    - own
            """)) { it.run('record') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['action:START:record', 'every-action:START', 'step', 'every-action:COMPLETE']

        cleanup:
        sm?.close()
    }

    def 'disableGlobalListeners: #value turns the state machine\'s action listeners off for this step'() {
        given:
        def definition = load("""\
            listeners:
              - id: global
                class: ${ActionAudit.name}
            steps:
              - id: record
                class: ${RecordingStep.name}
                disableGlobalListeners: ${value}
            """).onAnyActionStart('global')
        def sm = machine(definition) { it.run('record') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['step']

        cleanup:
        sm?.close()

        where:
        value << ['true', '[global]']
    }

    def 'onRejection is accepted on an action, for a fork of it to read'() {
        when:
        def sm = machine(load("""\
            steps:
              - id: record
                class: ${RecordingStep.name}
                onRejection: CALLER_RUNS
            """)) { it.run('record') }

        then:
        sm.entity(new Order()).transitionTo('b').success

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
        what                                 | sections                                                                                                           || message
        'a compensation of another context'  | "steps:\n  - id: s\n    class: ${ChildStep.name}\n    context: ${ChildCtx.name}\n    compensation: ${CtxUndo.name}\n" || "root.yml:8:19: step 's': class ${CtxUndo.name} declares Compensation's C as ${Ctx.name}, where this position needs ${ChildCtx.name}"
        'a typed compensation, untyped step' | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    compensation: ${CtxUndo.name}\n" ||"root.yml:7:19: step 's': class ${CtxUndo.name} declares Compensation's C as ${Ctx.name}, where this position needs java.lang.Object"
        'a route on a non-throwable'         | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    errorHandling:\n      - exception: ${String.name}\n        compensation: ${Undo.name}\n" || "root.yml:8:20: step 's': class java.lang.String is not a java.lang.Throwable"
        'a route without a compensation'     | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    errorHandling:\n      - exception: ${RuntimeException.name}\n" || "root.yml:8:9: step 's': 'compensation' is required"
        'a guard for another exception'      | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    errorHandling:\n      - exception: ${RuntimeException.name}\n        guard:\n          class: ${BoomGuard.name}\n        compensation: ${Undo.name}\n" || "root.yml:10:18: step 's': class ${BoomGuard.name} declares Predicate's T as ${IllegalStateException.name}, where this position needs ${RuntimeException.name}"
        'a guard with both forms'            | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    errorHandling:\n      - exception: ${RuntimeException.name}\n        guard:\n          class: ${BoomGuard.name}\n          expression: 'true'\n        compensation: ${Undo.name}\n" || "root.yml:11:11: step 's': exactly one of 'class', 'expression' is allowed; found both 'class' and 'expression'"
        'a VirtualMachineError route'        | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    errorHandling:\n      - exception: ${OutOfMemoryError.name}\n        compensation: ${Undo.name}\n" || "root.yml:8:20: step 's': Compensation route on step 's' is declared for ${OutOfMemoryError.name}, which can never match: no rollback runs after a VirtualMachineError"
        'an unknown hook'                    | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    listeners:\n      onEntry: []\n" || "root.yml:8:7: step 's': unknown key 'onEntry'; expected one of onStart, onComplete, onError"
        'an inline listener of another kind' | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    listeners:\n      onStart:\n        - id: l\n          class: ${StateAudit.name}\n" || "root.yml:10:18: step 's' > listener 'l': class ${StateAudit.name} is not a org.transflux.core.action.ActionListener"
        'an inline listener with a context'  | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    listeners:\n      onStart:\n        - id: l\n          class: ${ActionAudit.name}\n          context: ${Ctx.name}\n" || "root.yml:11:11: step 's' > listener 'l': unknown key 'context'; expected one of id, class, name, description, async, onRejection"
        'disableGlobalListeners: false'      | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    disableGlobalListeners: false\n" || "root.yml:7:29: step 's': 'disableGlobalListeners' must be true or a list of listener ids"
        'an empty disable list'              | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    disableGlobalListeners: []\n" || "root.yml:7:29: step 's': disableGlobalListeners on step 's' names no listener; to suppress every global listener declare disableAllGlobalListeners()"
        'an unknown policy'                  | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    onRejection: RETRY\n" || "root.yml:7:18: step 's': 'onRejection' must be one of DROP, FAIL, BLOCK, CALLER_RUNS, not 'RETRY'"
    }
}

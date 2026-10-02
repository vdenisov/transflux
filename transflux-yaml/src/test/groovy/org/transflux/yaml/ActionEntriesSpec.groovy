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

import org.transflux.core.ComponentFactory
import org.transflux.core.action.ActionSequence
import org.transflux.core.exception.TransfluxNoMatchException
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

import static org.transflux.yaml.LoaderFixtures.*

class ActionEntriesSpec extends Specification {

    def setup() {
        TRAIL.clear()
    }

    // ---- references ----

    def 'a run entry reaches a registered step, operation and choice, spelled the same'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                steps:
                  - id: record
                    class: ${RecordingStep.name}
                operations:
                  - id: flow
                    actions:
                      - run: record
                choices:
                  - id: route
                    branches:
                      - id: always
                        condition:
                          expression: 'true'
                        actions:
                          - run: record
                """,
            body: '- run: record\n- run: flow\n- run: route')).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success
        result.executedPath*.toString() == ['record', 'flow', 'flow/record', 'route', 'route/record']

        cleanup:
        sm?.close()
    }

    def 'a registered operation and choice run against the context they name'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                operations:
                  - id: flow
                    context: ${Ctx.name}
                    actions:
                      - step: in-flow
                        class: ${CtxStep.name}
                choices:
                  - id: route
                    context: ${Ctx.name}
                    branches:
                      - id: typed
                        condition:
                          id: ctx-bi
                          predicate: ${CtxBi.name}
                        actions:
                          - step: in-route
                            class: ${CtxStep.name}
                """,
            context: Ctx.name,
            body: '- run: flow\n- run: route')).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        result.success
        TRAIL == ['ctx-step:from-parent', 'ctx-step:from-parent']

        cleanup:
        sm?.close()
    }

    // ---- declarations ----

    def 'declarations nest, and an inline id is visible across its enclosing subtree'() {
        given:
        def sm = loadDocument(document(body: """\
            - operation: outer
              actions:
                - step: inner
                  class: ${RecordingStep.name}
                - choice: pick
                  branches:
                    - id: taken
                      condition:
                        expression: 'true'
                      actions:
                        - operation: deepest
                          actions:
                            - run: inner
            """)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success
        result.executedPath*.toString() ==
            ['outer', 'outer/inner', 'outer/pick', 'outer/pick/deepest', 'outer/pick/deepest/inner']

        cleanup:
        sm?.close()
    }

    def 'a choice runs its first matching branch, else its default'() {
        given:
        def sm = loadDocument(document(body: """\
            - choice: pick
              branches:
                - id: high
                  condition:
                    expression: 'priority > 5'
                  actions:
                    - step: on-high
                      class: ${RecordingStep.name}
                - id: any
                  condition:
                    id: priority-bi
                    predicate: ${PriorityBi.name}
                  actions:
                    - step: on-any
                      class: ${RecordingStep.name}
              default:
                actions:
                  - step: on-default
                    class: ${RecordingStep.name}
            """)).build()

        when:
        def result = sm.entity(new Order(priority: priority)).transitionTo('b')

        then:
        result.executedPath*.toString() == ['pick', "pick/${taken}".toString()]

        cleanup:
        sm?.close()

        where:
        priority || taken
        9        || 'on-high'
        1        || 'on-default'
    }

    def 'onNoMatch: #behavior decides a choice nothing matched'() {
        given:
        def sm = loadDocument(document(body: """\
            - choice: pick
              onNoMatch: ${behavior}
              branches:
                - id: never
                  condition:
                    expression: 'false'
                  actions:
                    - step: unreached
                      class: ${RecordingStep.name}
            """)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success == succeeded
        (result.error instanceof TransfluxNoMatchException) == !succeeded

        cleanup:
        sm?.close()

        where:
        behavior || succeeded
        'SILENT' || true
        'ERROR'  || false
    }

    def 'a declaration carries its own compensation and routes'() {
        given:
        def sm = loadDocument(document(body: """\
            - operation: guarded
              compensation: ${Undo.name}
              actions:
                - step: record
                  class: ${RecordingStep.name}
                  errorHandling:
                    - exception: ${IllegalStateException.name}
                      compensation: ${RouteUndo.name}
                - step: fail
                  class: ${FailingStep.name}
            """)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then: 'the members unwind first, then the container'
        !result.success
        TRAIL == ['step', 'route-undo', 'undo']

        cleanup:
        sm?.close()
    }

    def 'listeners declared on an inline declaration fire at its hooks'() {
        given:
        def sm = loadDocument(document(body: """\
            - step: record
              class: ${RecordingStep.name}
              listeners:
                onStart:
                  - id: own
                    class: ${ActionAudit.name}
                onComplete:
                  - own
            """)).build()

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['action:START:record', 'step', 'action:COMPLETE:record']

        cleanup:
        sm?.close()
    }

    // ---- context shapes ----

    def 'a declaration inherits the enclosing context, widens it, or maps it through #form'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                mappers:
                  - id: child-from-ctx
                    parentType: ${Ctx.name}
                    childType: ${ChildCtx.name}
                    class: ${ChildMapper.name}
                """,
            context: Ctx.name,
            body: """\
                - step: inherits
                  class: ${CtxStep.name}
                - step: widens
                  context: java.lang.Object
                  class: ${RecordingStep.name}
                - operation: mapped
                  context: ${ChildCtx.name}
                  mapper: ${mapper}
                  actions:
                    - step: child
                      class: ${ChildStep.name}
                """)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        result.success
        TRAIL == ['ctx-step:from-parent', 'step', 'child-step:mapped-from-parent']

        cleanup:
        sm?.close()

        where:
        form                  | mapper
        'a registered mapper' | 'child-from-ctx'
        'a class block'       | "{ class: ${ChildMapper.name} }"
    }

    def 'a run entry maps through #form, and writes back when it runs in line'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                steps:
                  - id: child
                    context: ${ChildCtx.name}
                    class: ${ChildStep.name}
                mappers:
                  - id: child-from-ctx
                    parentType: ${Ctx.name}
                    childType: ${ChildCtx.name}
                    mapTo: "new ${ChildCtx.name}()"
                    mapFrom:
                      chargeId: "chargeId"
                """,
            context: Ctx.name,
            body: "- run: child\n  mapper: ${mapper}")).build()
        def context = new Ctx()

        when:
        def result = sm.entity(new Order()).transitionTo('b', context)

        then:
        result.success
        TRAIL == ['child-step:null']
        context.chargeId == 'ch-1'

        cleanup:
        sm?.close()

        where:
        form                  | mapper
        'a registered mapper' | 'child-from-ctx'
        'an expression block' | "{ mapTo: \"new ${ChildCtx.name}()\", mapFrom: { chargeId: \"chargeId\" } }"
    }

    def 'a declaration narrowing the enclosing context without a mapper is refused at build'() {
        given:
        def definition = loadDocument(document(
            context: Ctx.name,
            body: "- step: narrows\n  context: ${ChildCtx.name}\n  class: ${ChildStep.name}"))

        when:
        definition.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
    }

    // ---- fork ----

    def 'fork: true hands a #verb member to the executor and leaves it off the path'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                steps:
                  - id: record
                    class: ${RecordingStep.name}
                """,
            body: entry)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success
        result.executedPath*.toString() == []
        new PollingConditions(timeout: 5).eventually {
            assert TRAIL == ['step']
        }

        cleanup:
        sm?.close()

        where:
        verb        | entry
        'run'       | '- run: record\n  fork: true\n  onRejection: CALLER_RUNS'
        'step'      | "- step: forked\n  fork: true\n  onRejection: CALLER_RUNS\n  class: ${RecordingStep.name}"
        'operation' | '- operation: forked\n  fork: true\n  actions:\n    - run: record'
        'choice'    | "- choice: forked\n  fork: true\n  branches:\n    - id: always\n      condition:\n        expression: 'true'\n      actions:\n        - run: record"
    }

    def 'a forked declaration maps into a context of its own'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                mappers:
                  - id: child-from-ctx
                    parentType: ${Ctx.name}
                    childType: ${ChildCtx.name}
                    class: ${ChildMapper.name}
                """,
            context: Ctx.name,
            body: "- step: forked\n  fork: true\n  context: ${ChildCtx.name}\n  mapper: child-from-ctx\n  class: ${ChildStep.name}")).build()

        when:
        sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        new PollingConditions(timeout: 5).eventually {
            assert TRAIL == ['child-step:mapped-from-parent']
        }

        cleanup:
        sm?.close()
    }

    // ---- the specification's own example ----

    def "the specification's complex-activation operation loads, builds and runs"() {
        given: 'its structure verbatim, with the example classes swapped for fixtures'
        def sm = loadResource('action-entries/complex-activation.transflux.yml').build()

        when:
        def result = sm.entity(new Order(priority: 9)).transitionTo('b', new Ctx())

        then: 'the synchronous members ran in order, the high-priority branch taken; forked ones are off the path'
        result.success
        result.executedPath*.toString() == [
            'complex-activation',
            'complex-activation/prepare-event-actor',
            'complex-activation/validate-prerequisites',
            'complex-activation/charge-card',
            'complex-activation/lock-resources',
            'complex-activation/notify-flow',
            'complex-activation/notify-flow/prepare-notifications',
            'complex-activation/notify-flow/send-notifications',
            'complex-activation/priority-routing',
            'complex-activation/priority-routing/high-priority-processing',
            'complex-activation/priority-routing/urgent-notification',
            'complex-activation/finalize']

        cleanup:
        sm?.close()
    }

    // ---- dispatch ----

    def "a run entry #entry reaches #overload"() {
        expect:
        dispatched("{run: a${entry}}") == [overload]

        where:
        entry                                                                   || overload
        ''                                                                      || 'run(String)'
        ', mapper: m'                                                           || 'run(String, String)'
        ", mapper: {class: ${ChildMapper.name}}"                                || 'run(String, ContextMapper)'
        ', fork: true'                                                          || 'fork(String)'
        ', fork: true, mapper: m'                                               || 'fork(String, String)'
        ", fork: true, mapper: {class: ${ChildMapper.name}}"                    || 'fork(String, ContextMapper)'
        ', fork: true, onRejection: DROP'                                       || 'fork(String, AsyncRejectionPolicy)'
        ', fork: true, onRejection: DROP, mapper: m'                            || 'fork(String, String, AsyncRejectionPolicy)'
        ", fork: true, onRejection: DROP, mapper: {class: ${ChildMapper.name}}" || 'fork(String, ContextMapper, AsyncRejectionPolicy)'
    }

    def "a '#verb:' entry #shape, forked: #forked, reaches #overload"() {
        given:
        def entry = "{${verb}: d" + (forked ? ', fork: true' : '') + SHAPES[shape].entry + '}'

        expect:
        dispatched(entry) == [overload]

        where:
        [verb, shape, forked] << [['step', 'operation', 'choice'], SHAPES.keySet(), [false, true]].combinations()
        overload = (forked ? 'fork' + verb.capitalize() : verb) + '(' + SHAPES[shape].parameters + ')'
    }

    // ---- refusals ----

    def 'refuses #what'() {
        when:
        loadDocument(document(body: body))

        then:
        def e = thrown(DefinitionLoadException)
        e.declarationPath() == "state machine > transition 't'${path}".toString()
        e.problem().startsWith(problem)

        where:
        what                                  | body                                                                                                                  || path                             | problem
        'two verbs'                           | '- run: a\n  step: b'                                                                                                 || ''                               | "exactly one of 'run', 'step', 'operation', 'choice' is allowed; found both 'run' and 'step'"
        'no verb'                             | '- fork: true'                                                                                                        || ''                               | "exactly one of 'run', 'step', 'operation', 'choice' is required"
        'listeners on a reference'            | '- run: a\n  listeners:\n    onStart: [x]'                                                                            || " > run 'a'"                     | "'listeners' belongs on the declaration of action 'a'"
        'a disable on a reference'            | '- run: a\n  disableGlobalListeners: true'                                                                            || " > run 'a'"                     | "'disableGlobalListeners' belongs on the declaration of action 'a'"
        'a context on a reference'            | "- run: a\n  context: ${Ctx.name}"                                                                                    || " > run 'a'"                     | "unknown key 'context'"
        'onRejection on an in-line reference' | '- run: a\n  onRejection: DROP'                                                                                       || " > run 'a'"                     | "'onRejection' applies to a forked reference; add 'fork: true'"
        'a mapper with no context'            | "- step: s\n  mapper: m\n  class: ${RecordingStep.name}"                                                              || " > step 's'"                    | "'mapper' produces the context this declaration runs against"
        'mapFrom beside a mapper class'       | "- run: a\n  mapper: {class: ${ChildMapper.name}, mapFrom: {}}"                                                       || " > run 'a' > mapper"            | "'mapFrom' needs 'mapTo' beside it"
        'mapFrom at a forked reference'       | "- run: a\n  fork: true\n  mapper: {mapTo: 'null', mapFrom: {x: y}}"                                                  || " > run 'a' > mapper"            | "'mapFrom' never runs at a forked call site"
        'mapFrom at a forked declaration'     | "- step: s\n  fork: true\n  context: java.lang.Object\n  mapper: {mapTo: 'null', mapFrom: {x: y}}"                    || " > step 's' > mapper"           | "'mapFrom' never runs at a forked call site"
        'types on a call-site mapper'         | "- run: a\n  mapper: {mapTo: 'null', parentType: x}"                                                                  || " > run 'a' > mapper"            | "unknown key 'parentType'"
        'a mapper over another parent'        | "- run: a\n  mapper: {class: ${ChildMapper.name}}"                                                                    || " > run 'a' > mapper"            | "class ${ChildMapper.name} declares ContextMapper's P as ${Ctx.name}, where this position needs java.lang.Object"
        'a step class for another context'    | "- step: s\n  class: ${CtxStep.name}"                                                                                 || " > step 's'"                    | "class ${CtxStep.name} declares Action's C as ${Ctx.name}, where this position needs java.lang.Object"
        'an operation with no actions'        | '- operation: o'                                                                                                      || " > operation 'o'"               | "'actions' is required"
        'a choice with no branches'           | '- choice: c'                                                                                                         || " > choice 'c'"                  | "'branches' is required"
        'a branch with no condition'          | '- choice: c\n  branches: [{id: b, actions: [{run: a}]}]'                                                             || " > choice 'c' > branch 'b'"     | "'condition' is required"
        'a branch with a compensation'        | '- choice: c\n  branches: [{id: b, condition: x, actions: [{run: a}], compensation: x}]'                              || " > choice 'c' > branch 'b'"     | "unknown key 'compensation'"
        'a default with a context'            | '- choice: c\n  branches: [{id: b, condition: x, actions: [{run: a}]}]\n  default: {context: x, actions: [{run: a}]}' || " > choice 'c' > default branch" | "unknown key 'context'"
    }

    /** How a declaration's context is written, and the parameters of the overload each shape reaches. */
    private static final Map<String, Map<String, String>> SHAPES = [
        'inheriting its context'             : [entry: '', parameters: 'String, Consumer'],
        'declaring a context'                : [entry: ", context: ${ChildCtx.name}", parameters: 'String, Class, Consumer'],
        'mapping by a registered id'         : [entry: ", context: ${ChildCtx.name}, mapper: m",
                                                parameters: 'String, Class, String, Consumer'],
        'mapping through a class'            : [entry: ", context: ${ChildCtx.name}, mapper: {class: ${ChildMapper.name}}",
                                                parameters: 'String, Class, ContextMapper, Consumer']]

    /**
     * Reads one {@code actions:} entry onto a sequence that records each call it receives, the
     * enclosing context being {@code Ctx}. A declaration's configurer never runs, so an entry
     * carries only the keys read before the call.
     *
     * @param entry the entry, in flow style
     *
     * @return each call, as the method's name and its parameter types
     */
    private List<String> dispatched(String entry) {
        def calls = []
        def sequence = Proxy.newProxyInstance(getClass().classLoader, [ActionSequence] as Class[],
            { proxy, Method method, Object[] args ->
                calls << "${method.name}(${method.parameterTypes*.simpleName.join(', ')})".toString()
                proxy
            } as InvocationHandler) as ActionSequence
        def readers = Readers.of(new Classes(getClass().classLoader, ComponentFactory.reflective()), Order,
            new DeclarationSites())
        def document = Document.parse([], 'doc.yml', null, new StringReader("actions:\n  - ${entry}\n"), Document.DEFAULT_CODE_POINT_LIMIT)
        readers.actions().list(NodeMap.of(document, document.root(), null, 'the document'), sequence, Ctx)
        return calls
    }

    /**
     * Composes a root document of states {@code a} and {@code b} and a transition {@code t} between
     * them, resolved and applied through {@code state}.
     *
     * @param parts any of {@code sections} (above {@code stateMachine:}), {@code context} (the
     *        transition's) and {@code body} (its {@code actions:} list)
     *
     * @return the document
     */
    private static String document(Map<String, String> parts) {
        return 'apiVersion: transflux/v1\n\n' +
            (parts.sections ? parts.sections.stripIndent().trim() + '\n\n' : '') +
            'stateMachine:\n' +
            "  entityType: ${Order.name}\n" +
            "  stateResolver:\n    expression: 'state'\n" +
            "  stateApplier:\n    expression: 'state'\n" +
            '  states:\n    - id: a\n    - id: b\n' +
            '  transitions:\n    - id: t\n      from: a\n      to: b\n' +
            (parts.context ? "      context: ${parts.context}\n" : '') +
            (parts.body ? '      actions:\n' + indent(parts.body, 8) + '\n' : '')
    }

    private static String indent(String text, int spaces) {
        return text.stripIndent().trim().readLines().collect { ' ' * spaces + it }.join('\n')
    }
}

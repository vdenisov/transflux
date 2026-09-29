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

import org.transflux.core.trigger.DataTrigger
import org.transflux.core.trigger.EventTrigger
import org.transflux.core.trigger.ManualTrigger
import spock.lang.Specification

import static org.transflux.yaml.LoaderFixtures.*

class ComponentSectionsSpec extends Specification {

    def setup() {
        TRAIL.clear()
    }

    def 'a registered step runs wherever a transition names it'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: record
                name: Record
                description: Records that it ran
                class: ${RecordingStep.name}
            """)) { it.run('record') }

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success
        TRAIL == ['step']

        cleanup:
        sm?.close()
    }

    def 'a step registered against a context runs against it'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: typed
                context: ${Ctx.name}
                class: ${CtxStep.name}
            """), Ctx) { it.run('typed') }

        when:
        sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        TRAIL == ['ctx-step:from-parent']

        cleanup:
        sm?.close()
    }

    def 'a generic step named raw loads, its entity argument left to the runtime'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: generic
                class: ${GenericStep.name}
            """)) { it.run('generic') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['generic-step']

        cleanup:
        sm?.close()
    }

    def 'a registered #form condition gates the transition naming it'() {
        given:
        def sm = machine(load("""\
            conditions:
              - id: urgent
                ${declaration}
            """)) { it.preCondition('urgent') }

        expect:
        !sm.entity(new Order(priority: 1)).transitionTo('b').success
        sm.entity(new Order(priority: 9)).transitionTo('b').success

        cleanup:
        sm?.close()

        where:
        form                      | declaration
        'class'                   | "class: ${PriorityCondition.name}"
        'BiPredicate'             | "predicate: ${PriorityBi.name}"
        'Predicate'               | "predicate: ${PriorityPredicate.name}"
        'expression'              | "expression: 'priority > 5'"
        'typed expression'        | "expression: 'priority > 5'\n                context: ${Object.name}"
    }

    def 'a mapper registered with a class maps the context at the call site naming it'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: child
                context: ${ChildCtx.name}
                class: ${ChildStep.name}
            mappers:
              - id: child-from-parent
                name: Child from parent
                parentType: ${Ctx.name}
                childType: ${ChildCtx.name}
                class: ${ChildMapper.name}
            """), Ctx) { it.run('child', 'child-from-parent') }

        when:
        sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        TRAIL == ['child-step:mapped-from-parent']

        cleanup:
        sm?.close()
    }

    def 'a mapper registered with expressions maps, and writes back in document order'() {
        given:
        def sm = machine(load("""\
            steps:
              - id: child
                context: ${ChildCtx.name}
                class: ${ChildStep.name}
            mappers:
              - id: child-from-parent
                parentType: ${Ctx.name}
                childType: ${ChildCtx.name}
                mapTo: "new ${ChildCtx.name}()"
                mapFrom:
                  chargeId: "chargeId"
                  status: "'CHARGED'"
                  note: "#parent.chargeId + '/' + note"
            """), Ctx) { it.run('child', 'child-from-parent') }
        def context = new Ctx()

        when:
        sm.entity(new Order()).transitionTo('b', context)

        then: 'the enum property takes the string, and #parent sees the assignments before it'
        TRAIL == ['child-step:null']
        context.chargeId == 'ch-1'
        context.status == Status.CHARGED
        context.note == 'ch-1/null'

        cleanup:
        sm?.close()
    }

    def 'a manual trigger fires through its own pre-conditions'() {
        given:
        def sm = machine(load("""\
            triggers:
              - id: go
                type: manual
                name: Go
                description: Moves the order on
                preConditions:
                  - expression: 'priority > 5'
                  - id: urgent-too
                    predicate: ${PriorityPredicate.name}
            """)) { it.addTrigger('go') }

        expect:
        sm.getTrigger('go') instanceof ManualTrigger
        sm.getTrigger('go').name == 'Go'
        !sm.entity(new Order(priority: 1)).fire('go').success
        sm.entity(new Order(priority: 9)).fire('go').success

        cleanup:
        sm?.close()
    }

    def 'an event trigger fires for a matching event through its #form filter'() {
        given:
        def sm = machine(load("""\
            triggers:
              - id: confirmed
                type: event
                event: VALIDATED
                filter:
                  ${filter}
            """)) { it.addTrigger('confirmed') }

        expect:
        sm.getTrigger('confirmed') instanceof EventTrigger
        !sm.entity(new Order()).processEvent('VALIDATED', 'PENDING').fired()
        sm.entity(new Order()).processEvent('VALIDATED', 'CONFIRMED').fired()

        cleanup:
        sm?.close()

        where:
        form                  | filter
        'expression'          | "expression: \"#event == 'CONFIRMED'\""
        'BiPredicate class'   | "class: ${ConfirmedEvent.name}"
        'Predicate class'     | "class: ${ConfirmedEventOnly.name}"
    }

    def 'a data trigger fires when its #form gate holds'() {
        given:
        def sm = machine(load("""\
            conditions:
              - id: urgent
                expression: 'priority > 5'
            triggers:
              - id: escalate
                type: data
                condition: ${condition}
            """)) { it.addTrigger('escalate') }

        expect:
        sm.getTrigger('escalate') instanceof DataTrigger
        !sm.entity(new Order(priority: 1)).processDataChange().fired()
        sm.entity(new Order(priority: 9)).processDataChange().fired()

        cleanup:
        sm?.close()

        where:
        form           | condition
        'referenced'   | 'urgent'
        'inline'       | "{ id: urgent-inline, class: ${PriorityCondition.name} }"
        'expression'   | "{ expression: 'priority > 5' }"
    }

    def 'a trigger registered against a context accepts that context'() {
        given:
        def sm = machine(load("""\
            triggers:
              - id: go
                type: manual
                context: ${Ctx.name}
                preConditions:
                  - id: ctx-ok
                    predicate: ${CtxBi.name}
            """), Ctx) { it.addTrigger('go') }

        expect:
        sm.entity(new Order()).fire('go', new Ctx()).success

        cleanup:
        sm?.close()
    }

    def 'a registered listener is attached by id'() {
        given:
        def sm = machine(load("""\
            listeners:
              - id: audit
                class: ${TransitionAudit.name}
            """)) { it.onStart('audit').onComplete('audit') }

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['transition:START', 'transition:COMPLETE']

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
        what                            | sections                                                                              || message
        'a section that is not a list'  | 'steps: {}\n'                                                                         || "root.yml:4:8: 'steps' must be a list"
        'an entry that is not a map'    | 'steps:\n  - record\n'                                                                || 'root.yml:5:5: a step must be a mapping'
        'a step without an id'          | "steps:\n  - class: ${RecordingStep.name}\n"                                          || "root.yml:5:5: 'id' is required"
        'a step without a class'        | 'steps:\n  - id: s\n'                                                                 || "root.yml:5:5: step 's': 'class' is required"
        'an unknown step key'           | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n    extra: 1\n"               || "root.yml:7:5: step 's': unknown key 'extra'; expected one of id, context, class, name, description, onRejection, compensation, errorHandling, listeners, disableGlobalListeners"
        'a class that is not an action' | "steps:\n  - id: s\n    class: ${Undo.name}\n"                                        || "root.yml:6:12: step 's': class ${Undo.name} is not a org.transflux.core.action.Action"
        'another context argument'      | "steps:\n  - id: s\n    context: ${ChildCtx.name}\n    class: ${CtxStep.name}\n"    || "root.yml:7:12: step 's': class ${CtxStep.name} declares Action's C as ${Ctx.name}, where this position needs ${ChildCtx.name}"
        'an id claimed twice'           | "steps:\n  - id: s\n    class: ${RecordingStep.name}\n  - id: s\n    class: ${RecordingStep.name}\n" || "root.yml:7:9: step 's': Step id 's' is already registered by another step. Ids are unique across the state machine wherever they are declared, so give one of them another id, or declare it once and reference it by id; first declared at root.yml:5:9"
        'a condition with a name'       | "conditions:\n  - id: c\n    expression: 'true'\n    name: C\n"                     || "root.yml:7:5: condition 'c': unknown key 'name'; expected one of context, class, predicate, expression, id"
        'a condition with two forms'    | "conditions:\n  - id: c\n    expression: 'true'\n    class: ${PriorityCondition.name}\n" || "root.yml:6:5: exactly one of 'class', 'predicate', 'expression' is allowed; found both 'class' and 'expression'"
        'a registered condition sans id'| "conditions:\n  - expression: 'true'\n"                                               || "root.yml:5:5: 'id' is required"
        'a malformed expression'        | "conditions:\n  - id: c\n    expression: 'priority >'\n"                            || "root.yml:6:17: condition 'c': Invalid SpEL expression 'priority >': Expression [priority >] @9: EL1042E: Problem parsing right operand"
        'a mapper with both bodies'     | "mappers:\n  - id: m\n    parentType: ${Ctx.name}\n    childType: ${ChildCtx.name}\n    class: ${ChildMapper.name}\n    mapTo: x\n" || "root.yml:9:5: mapper 'm': exactly one of 'class', 'mapTo' is allowed; found both 'class' and 'mapTo'"
        'mapFrom beside a class'        | "mappers:\n  - id: m\n    parentType: ${Ctx.name}\n    childType: ${ChildCtx.name}\n    class: ${ChildMapper.name}\n    mapFrom: {}\n" || "root.yml:9:5: mapper 'm': 'mapFrom' needs 'mapTo' beside it; a class maps back itself"
        'a mapper of other types'       | "mappers:\n  - id: m\n    parentType: ${ChildCtx.name}\n    childType: ${ChildCtx.name}\n    class: ${ChildMapper.name}\n" || "root.yml:8:12: mapper 'm': class ${ChildMapper.name} declares ContextMapper's P as ${Ctx.name}, where this position needs ${ChildCtx.name}"
        'an unparsable mapTo'           | "mappers:\n  - id: m\n    parentType: ${Ctx.name}\n    childType: ${ChildCtx.name}\n    mapTo: 'new ('\n" || "root.yml:8:12: mapper 'm': invalid SpEL expression 'new (': Expression [new (] @4: EL1043E: Unexpected token. Expected 'qualified ID' but was 'lparen(()'"
        'an unknown trigger type'       | 'triggers:\n  - id: t\n    type: cron\n'                                             || "root.yml:6:11: trigger 't': 'type' must be one of manual, event, data, not 'cron'"
        'another kind\'s key'           | 'triggers:\n  - id: t\n    type: manual\n    event: E\n'                            || "root.yml:7:5: trigger 't': unknown key 'event'; expected one of id, type, context, name, description, preConditions"
        'an event trigger with no event'| 'triggers:\n  - id: t\n    type: event\n'                                            || "root.yml:5:5: trigger 't': 'event' is required"
        'a data trigger with no gate'   | 'triggers:\n  - id: t\n    type: data\n'                                             || "root.yml:5:5: trigger 't': 'condition' is required"
        'a filter implementing both'    | "triggers:\n  - id: t\n    type: event\n    event: E\n    filter:\n      class: ${BothPredicates.name}\n" || "root.yml:9:14: trigger 't': class ${BothPredicates.name} implements both BiPredicate and Predicate; implement one"
        'a filter over another event'   | "triggers:\n  - id: t\n    type: event\n    event: E\n    filter:\n      class: ${PriorityBi.name}\n" || "root.yml:9:14: trigger 't': class ${PriorityBi.name} declares BiPredicate's T as ${Order.name}, where this position needs java.lang.Object"
    }
}

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

class ConditionDescriptorsSpec extends Specification {

    def 'a pre-condition list mixes every form: #form'() {
        given:
        def sm = machine(load("""\
            conditions:
              - id: urgent
                expression: 'priority > 5'
            triggers:
              - id: go
                type: manual
                preConditions:
                  - ${descriptor}
            """)) { it.addTrigger('go') }

        expect:
        !sm.entity(new Order(priority: 1)).fire('go').success
        sm.entity(new Order(priority: 9)).fire('go').success

        cleanup:
        sm?.close()

        where:
        form                  | descriptor
        'a reference'         | 'urgent'
        'a class'             | "{ id: c, class: ${PriorityCondition.name} }"
        'a BiPredicate'       | "{ id: c, predicate: ${PriorityBi.name} }"
        'a Predicate'         | "{ id: c, predicate: ${PriorityPredicate.name} }"
        'a named expression'  | "{ id: c, expression: 'priority > 5' }"
        'an id-less one'      | "{ expression: 'priority > 5' }"
    }

    def 'a condition on a trigger declaring no context is checked against Object'() {
        when:
        load("triggers:\n  - id: t\n    type: manual\n    preConditions:\n      - { id: c, predicate: ${CtxBi.name} }\n")

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml:8:29: trigger 't' > condition 'c': class ${CtxBi.name} declares BiPredicate's U as ${Ctx.name}, where this position needs java.lang.Object"
    }

    def 'refuses #what'() {
        when:
        load("triggers:\n  - id: t\n    type: manual\n    context: ${Ctx.name}\n    preConditions:\n      - ${descriptor}\n")

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        what                             | descriptor                                                || message
        'a list for a descriptor'        | '[a]'                                                     || "root.yml:9:9: trigger 't': a condition must be a mapping"
        'no form'                        | '{ id: c }'                                               || "root.yml:9:9: trigger 't': exactly one of 'class', 'predicate', 'expression' is required"
        'a class without an id'          | "{ class: ${PriorityCondition.name} }"                    || "root.yml:9:9: trigger 't': 'id' is required"
        'a name'                         | "{ id: c, expression: 'true', name: C }"                  || "root.yml:9:38: trigger 't' > condition 'c': unknown key 'name'; expected one of class, predicate, expression, id"
        'a class of another kind'        | "{ id: c, class: ${PriorityBi.name} }"                    || "root.yml:9:25: trigger 't' > condition 'c': class ${PriorityBi.name} is not a org.transflux.core.condition.Condition"
        'another context'                | "{ id: c, class: ${PriorityCondition.name} }"             || "root.yml:9:25: trigger 't' > condition 'c': class ${PriorityCondition.name} declares Condition's C as java.lang.Object, where this position needs ${Ctx.name}"
        'a predicate of another context' | "{ id: c, predicate: ${PriorityBi.name} }"                || "root.yml:9:29: trigger 't' > condition 'c': class ${PriorityBi.name} declares BiPredicate's U as java.lang.Object, where this position needs ${Ctx.name}"
        'a predicate implementing both'  | "{ id: c, predicate: ${BothPredicates.name} }"            || "root.yml:9:29: trigger 't' > condition 'c': class ${BothPredicates.name} implements both BiPredicate and Predicate; implement one"
        'a predicate that is neither'    | "{ id: c, predicate: ${RecordingStep.name} }"             || "root.yml:9:29: trigger 't' > condition 'c': class ${RecordingStep.name} is neither a BiPredicate nor a Predicate"
    }
}

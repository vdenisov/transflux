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

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

import static org.transflux.yaml.LoaderFixtures.ChildCtx
import static org.transflux.yaml.LoaderFixtures.Ctx
import static org.transflux.yaml.LoaderFixtures.EnumOrder
import static org.transflux.yaml.LoaderFixtures.Order
import static org.transflux.yaml.LoaderFixtures.Status

class ExpressionsSpec extends Specification {

    def 'mapTo is evaluated against the parent context'() {
        given:
        def mapper = Expressions.mapper(map("mapTo: \"new ${ChildCtx.name}()\"\n"))

        expect:
        mapper.mapTo(new Ctx()) instanceof ChildCtx
    }

    def 'without mapFrom, a mapper writes nothing back'() {
        given:
        def mapper = Expressions.mapper(map("mapTo: 'note'\n"))
        def parent = new Ctx()

        when:
        mapper.mapFrom(parent, new ChildCtx(chargeId: 'ch-9'))

        then:
        mapper.mapTo(parent) == 'from-parent'
        parent.chargeId == null
    }

    def 'mapFrom assigns into the parent in document order, converting to the property type'() {
        given:
        def mapper = Expressions.mapper(map('''\
            mapTo: 'note'
            mapFrom:
              chargeId: "chargeId"
              status: "'CHARGED'"
              note: "#parent.chargeId + '/' + note"
            '''.stripIndent()))
        def parent = new Ctx()

        when:
        mapper.mapFrom(parent, new ChildCtx(note: 'child'))

        then:
        parent.chargeId == 'ch-1'
        parent.status == Status.CHARGED
        parent.note == 'ch-1/child'
    }

    def 'a failing evaluation names the expression and the failure type, not the values'() {
        given:
        def mapper = Expressions.mapper(map("mapTo: 'missing'\nmapFrom:\n  status: \"'SECRET-VALUE'\"\n"))

        when:
        mapper.mapTo(new Ctx())

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Failed to evaluate SpEL expression 'missing': org.springframework.expression.spel.SpelEvaluationException"

        when:
        mapper.mapFrom(new Ctx(), new ChildCtx())

        then:
        def assignment = thrown(TransfluxValidationException)
        assignment.message == "Failed to assign mapFrom target 'status': org.springframework.expression.spel.SpelEvaluationException"
        !assignment.message.contains('SECRET-VALUE')
    }

    def 'a resolver reads the state off the entity, an enum contributing its name'() {
        given:
        def resolver = Expressions.resolver(map("expression: 'status'\n"))

        expect:
        resolver.resolveState(new EnumOrder(status: Status.CHARGED)) == 'CHARGED'
    }

    def 'a resolver renders a non-enum state through toString, and passes null through'() {
        given:
        def resolver = Expressions.resolver(map("expression: 'state'\n"))

        expect:
        resolver.resolveState(new Order(state: 'a')) == 'a'
        resolver.resolveState(new Order(state: null)) == null
    }

    def 'a resolver binds the entity under #entity as well as at the root'() {
        given:
        def resolver = Expressions.resolver(map("expression: \"#entity.state + '/' + priority\"\n"))

        expect:
        resolver.resolveState(new Order(state: 'a', priority: 7)) == 'a/7'
    }

    def 'an applier assigns the state id, converting it to an enum-typed property'() {
        given:
        def applier = Expressions.applier(map("expression: 'status'\n"))
        def order = new EnumOrder()

        when:
        applier.applyState(order, 'CHARGED')

        then:
        order.status == Status.CHARGED
    }

    def 'an applier assigns a String-typed property'() {
        given:
        def applier = Expressions.applier(map("expression: 'state'\n"))
        def order = new Order()

        when:
        applier.applyState(order, 'b')

        then:
        order.state == 'b'
    }

    def 'an applier that cannot assign names the target and the failure type, not the value'() {
        given:
        def applier = Expressions.applier(map("expression: 'priority'\n"))

        when:
        applier.applyState(new Order(), 'SECRET-VALUE')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Failed to assign state applier target 'priority': org.springframework.expression.spel.SpelEvaluationException"
        !e.message.contains('SECRET-VALUE')
    }

    def 'a guard judges the failure it is handed'() {
        given:
        def guard = Expressions.guard(map("expression: \"message == 'boom'\"\n"))

        expect:
        guard.test(new IllegalStateException('boom'))
        !guard.test(new IllegalStateException('other'))
    }

    def 'a guard that does not yield a boolean throws'() {
        given:
        def guard = Expressions.guard(map("expression: 'message'\n"))

        when:
        guard.test(new IllegalStateException('boom'))

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Guard expression 'message' must evaluate to boolean but returned java.lang.String"
    }

    def 'refuses #what at its line'() {
        when:
        read(map(text))

        then:
        def e = thrown(DefinitionLoadException)
        e.message.startsWith(message)

        where:
        what                     | text                                           | read                                || message
        'a malformed mapTo'      | "mapTo: 'a +'\n"                               | { Expressions.mapper(it) }          || "doc.yml:1:8: invalid SpEL expression 'a +': "
        'a malformed target'     | "mapTo: 'a'\nmapFrom:\n  'a +': 'b'\n"         | { Expressions.mapper(it) }          || "doc.yml:3:3: invalid SpEL expression 'a +': "
        'a malformed value'      | "mapTo: 'a'\nmapFrom:\n  a: 'b +'\n"           | { Expressions.mapper(it) }          || "doc.yml:3:6: invalid SpEL expression 'b +': "
        'a mapFrom not a map'    | "mapTo: 'a'\nmapFrom: [a]\n"                   | { Expressions.mapper(it) }          || "doc.yml:2:10: 'mapFrom' must be a mapping"
        'a malformed guard'      | "expression: 'a +'\n"                          | { Expressions.guard(it) }           || "doc.yml:1:13: invalid SpEL expression 'a +': "
    }

    private static NodeMap map(String text) {
        def document = Document.parse('doc.yml', null, new StringReader(text))
        return NodeMap.of(document, document.root(), null, 'the document')
    }
}

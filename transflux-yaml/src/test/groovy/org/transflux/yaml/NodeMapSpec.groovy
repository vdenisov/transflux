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

import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class NodeMapSpec extends Specification {

    def 'reads each scalar kind'() {
        given:
        def map = map('s: text\nb: false\ni: 16\ne: BLOCK\nl: [x, y]\nm: {k: v}\nn:\n')

        expect:
        map.requiredString('s') == 'text'
        !map.optionalBoolean('b')
        map.optionalInt('i') == 16
        map.optionalEnum('e', AsyncRejectionPolicy) == AsyncRejectionPolicy.BLOCK
        map.optionalList('l')*.value == ['x', 'y']
        map.optionalMap('m').requiredString('k') == 'v'
        map.optionalString('n') == null
        map.optionalString('absent') == null
    }

    def 'refuses #what'() {
        given:
        def map = map(text)

        when:
        read(map)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        what                  | text           | read                                                       || message
        'a missing key'       | 'a: 1\n'       | { NodeMap m -> m.requiredString('b') }                     || "doc.yml:1:1: 'b' is required"
        'an empty value'      | 'b:\n'         | { NodeMap m -> m.requiredString('b') }                     || "doc.yml:1:1: 'b' requires a value"
        'a list for a string' | 'b: [1]\n'     | { NodeMap m -> m.requiredString('b') }                     || "doc.yml:1:4: 'b' must be a string"
        'yes for a boolean'   | 'b: yes\n'     | { NodeMap m -> m.optionalBoolean('b') }                    || "doc.yml:1:4: 'b' must be true or false, not 'yes'"
        'a word for a number' | 'b: many\n'    | { NodeMap m -> m.optionalInt('b') }                        || "doc.yml:1:4: 'b' must be a whole number, not 'many'"
        'an unknown constant' | 'b: WAIT\n'    | { NodeMap m -> m.optionalEnum('b', AsyncRejectionPolicy) } || "doc.yml:1:4: 'b' must be one of DROP, FAIL, BLOCK, CALLER_RUNS, not 'WAIT'"
        'a string for a list' | 'b: x\n'       | { NodeMap m -> m.optionalList('b') }                       || "doc.yml:1:4: 'b' must be a list"
        'a string for a map'  | 'b: x\n'       | { NodeMap m -> m.requiredMap('b') }                        || "doc.yml:1:4: 'b' must be a mapping"
        'none of a choice'    | 'a: 1\n'       | { NodeMap m -> m.exactlyOneOf('x', 'y') }                  || "doc.yml:1:1: exactly one of 'x', 'y' is required"
        'two of a choice'     | 'x: 1\ny: 2\n' | { NodeMap m -> m.exactlyOneOf('x', 'y') }                  || "doc.yml:2:1: exactly one of 'x', 'y' is allowed; found both 'x' and 'y'"
    }

    def 'exactly one of several alternatives is named back'() {
        expect:
        map('y: 2\n').exactlyOneOf('x', 'y') == 'y'
    }

    def 'an unknown key is reported where it is written, beside every key asked for'() {
        given:
        def map = map('id: a\nnmae: b\n').within("state 'a'")
        map.requiredString('id')
        map.optionalString('name')

        when:
        map.rejectUnknownKeys()

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "doc.yml:2:1: state 'a': unknown key 'nmae'; expected one of id, name"
    }

    def 'a position that allows nothing says so'() {
        when:
        map('a: 1\n').rejectUnknownKeys()

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "doc.yml:1:1: unknown key 'a'; expected none"
    }

    def 'declaration paths nest'() {
        given:
        def map = map('a: 1\n').within("transition 't'").within("operation 'op'")

        when:
        map.requiredString('b')

        then:
        def e = thrown(DefinitionLoadException)
        e.declarationPath() == "transition 't' > operation 'op'"
    }

    def 'a rejection from a definition call is reported at the node it was made for'() {
        given:
        def map = map('steps:\n  - id: a\n')
        def entry = map.optionalList('steps')[0]
        def rejection = new TransfluxValidationException("Action ID 'a' is already registered")

        when:
        map.at(entry, { throw rejection })

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "doc.yml:2:5: Action ID 'a' is already registered"
        e.cause.is(rejection)
    }

    def 'a call that succeeds returns its value'() {
        given:
        def map = map('a: 1\n')

        expect:
        map.at(map.requiredNode('a'), { 'done' }) == 'done'
    }

    def 'a load error from a call passes through unwrapped'() {
        given:
        def map = map('a: 1\n')
        def own = new DefinitionLoadException('x', null, null, null, null, 'own', null)

        when:
        map.at(map.requiredNode('a'), { throw own })

        then:
        def e = thrown(DefinitionLoadException)
        e.is(own)
    }

    private static NodeMap map(String text) {
        def document = Document.parse('doc.yml', null, new StringReader(text))
        return NodeMap.of(document, document.root(), null, 'the document')
    }
}

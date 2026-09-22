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

import org.yaml.snakeyaml.nodes.MappingNode
import spock.lang.Specification

class DocumentSpec extends Specification {

    def 'parses one document, keeping every position'() {
        when:
        def document = parse('apiVersion: transflux/v1\nstateMachine:\n  entityType: x\n')

        then:
        document.identifier() == 'doc.yml'
        document.root() instanceof MappingNode
        (document.root() as MappingNode).value[1].valueNode.startMark.line == 2
    }

    def 'refuses #what at the line it appears on'() {
        when:
        parse(text)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        what                    | text                                     || message
        'an anchor'             | 'a: &base\n  b: 1\nc: 2\n'               || "doc.yml:1:4: anchors and aliases are not supported ('&base')"
        'an alias'              | 'a: 1\nb: *a\n'                          || "doc.yml:2:4: not valid YAML: found undefined alias a"
        'a merge key'           | 'a:\n  <<: {b: 1}\n'                     || "doc.yml:2:3: merge keys ('<<') are not supported"
        'a second document'     | 'a: 1\n---\nb: 2\n'                      || 'doc.yml:3:1: a definition is one YAML document; this is a second one'
        'a language tag'        | 'a: !!java.util.Date 1\n'                || "doc.yml:1:4: tag '!!java.util.Date' is not supported"
        'a local tag'           | 'a: !custom 1\n'                         || "doc.yml:1:4: tag '!custom' is not supported"
        'a set'                 | 'a: !!set {b: null}\n'                   || "doc.yml:1:4: tag '!!set' is not supported"
        'a duplicate key'       | 'a: 1\nb:\n  c: 1\n  c: 2\n'             || "doc.yml:4:3: duplicate key 'c'"
        'a complex key'         | '? [a]\n: 1\n'                           || 'doc.yml:1:3: a key must be a plain string'
        'text that is not YAML' | 'a: [1\n'                                || 'doc.yml:2:1: not valid YAML: expected \',\' or \']\', but got <stream end>'
        'an empty stream'       | ''                                       || 'doc.yml: the document is empty'
    }

    def 'accepts plain scalars of every core type, an explicit core tag included'() {
        expect:
        parse('s: text\ni: 1\nf: 1.5\nb: true\nn: null\nd: 2025-01-01\nt: !!str 5\nl: [a, b]\n') != null
    }

    def 'an error carries the location beside the identifier'() {
        when:
        Document.parse('doc.yml', '/defs/doc.yml', new StringReader('a: !x 1\n'))

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "doc.yml (/defs/doc.yml):1:4: tag '!x' is not supported"
    }

    private static Document parse(String text) {
        return Document.parse('doc.yml', null, new StringReader(text))
    }
}

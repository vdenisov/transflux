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

import org.transflux.core.StateMachine
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.yaml.source.DefinitionResource
import org.transflux.yaml.source.DefinitionSource
import spock.lang.Specification

import static org.transflux.yaml.LoaderFixtures.*

class YamlDefinitionLoaderImportSpec extends Specification {

    private static final String LIBRARY = """\
        apiVersion: transflux/v1
        steps:
          - id: record
            class: ${RecordingStep.name}
        conditions:
          - id: always
            expression: 'true'
        triggers:
          - id: go
            type: manual
        """

    private final Map<String, Integer> opens = [:].withDefault { 0 }

    def setup() {
        TRAIL.clear()
    }

    def 'a root uses what the documents it imports register'() {
        given:
        def sm = build(loader('root.yml': root('lib.yml'), 'lib.yml': LIBRARY))

        when:
        def result = sm.entity(new Order()).fire('go')

        then:
        result.success
        TRAIL == ['step']

        cleanup:
        sm?.close()
    }

    def 'a library imports libraries of its own'() {
        given:
        def sm = build(loader(
            'root.yml': root('mid.yml'),
            'mid.yml' : imports('lib.yml'),
            'lib.yml' : LIBRARY))

        expect:
        sm.entity(new Order()).fire('go').success

        cleanup:
        sm?.close()
    }

    def 'a document two imports reach is read once, and declares its components once'() {
        given:
        def loader = loader(
            'root.yml' : root('left.yml', 'right.yml'),
            'left.yml' : imports('lib.yml'),
            'right.yml': imports('lib.yml'),
            'lib.yml'  : LIBRARY)

        when:
        def sm = build(loader)

        then:
        opens['lib.yml'] == 1
        sm.entity(new Order()).fire('go').success

        cleanup:
        sm?.close()
    }

    def 'every load reads the imports through the source again'() {
        given:
        def loader = loader('root.yml': root('lib.yml'), 'lib.yml': LIBRARY)

        when:
        2.times { loader.load('root.yml', Order) }

        then:
        opens['lib.yml'] == 2
    }

    def 'an import the source does not have is reported at the entry, with the chain that reached it'() {
        when:
        loader(documents).load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message
        e.importChain() == chain

        where:
        documents                                                     || message                                                        | chain
        ['root.yml': root('gone.yml')]                                || "root.yml:3:5: import 'gone.yml': no such document"             | []
        ['root.yml': root('lib.yml'), 'lib.yml': imports('gone.yml')] || "root.yml -> lib.yml:3:5: import 'gone.yml': no such document" | ['root.yml']
    }

    def 'a circular import is refused, naming the cycle'() {
        when:
        loader(documents).load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        documents                                                                         || message
        ['root.yml': root('root.yml')]                                                    || 'root.yml:3:5: circular import: root.yml -> root.yml'
        ['root.yml': root('a.yml'), 'a.yml': imports('root.yml')]                         || 'root.yml -> a.yml:3:5: circular import: root.yml -> a.yml -> root.yml'
        ['root.yml': root('a.yml'), 'a.yml': imports('b.yml'), 'b.yml': imports('a.yml')] || 'root.yml -> a.yml -> b.yml:3:5: circular import: a.yml -> b.yml -> a.yml'
    }

    def 'an imported document is refused when it is not a library'() {
        when:
        loader('root.yml': root('lib.yml'), 'lib.yml': library).load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message
        e.importChain() == ['root.yml']

        where:
        library                                                                  || message
        "apiVersion: transflux/v1\nstateMachine:\n  entityType: ${Order.name}\n" || 'root.yml -> lib.yml:2:1: only the root document declares a state machine; this one is imported'
        'steps: []\n'                                                            || "root.yml -> lib.yml:1:1: 'apiVersion' is required"
        'apiVersion: transflux/v0\n'                                             || "root.yml -> lib.yml:1:13: apiVersion must be 'transflux/v1', not 'transflux/v0'"
        'apiVersion: transflux/v1\nstats: []\n'                                  || "root.yml -> lib.yml:2:1: unknown key 'stats'; expected one of apiVersion, imports, steps, operations, choices, conditions, mappers, triggers, listeners"
        'a: [1\n'                                                                || "root.yml -> lib.yml:2:1: not valid YAML: expected ',' or ']', but got <stream end>"
    }

    def 'an import entry is an identifier'() {
        when:
        loader('root.yml': "apiVersion: transflux/v1\nimports:${imports}\nstateMachine:\n  entityType: ${Order.name}\n")
            .load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        imports        || message
        '\n  - {a: b}' || 'root.yml:3:5: an import is the identifier of a document'
        "\n  - ''"     || 'root.yml:3:5: an import is the identifier of a document'
        '\n  - ~'      || 'root.yml:3:5: an import is the identifier of a document'
        '\n  - null'   || 'root.yml:3:5: an import is the identifier of a document'
        ' lib.yml'     || "root.yml:2:10: 'imports' must be a list"
        // Identifiers are opaque, so a number is one: it reaches the source as written.
        '\n  - 42'     || "root.yml:3:5: import '42': no such document"
    }

    def 'an import the source refuses is reported at the entry, with the chain that reached it'() {
        given:
        Map<String, String> documents = ['root.yml': root('lib.yml'), 'lib.yml': imports('../outside.yml')]
        DefinitionSource source = { String id ->
            if (id.startsWith('..')) {
                throw new TransfluxValidationException("Definition identifier '${id}' leaves the root")
            }
            return Optional.of(new DefinitionResource(id, new ByteArrayInputStream(documents[id].bytes)))
        }

        when:
        YamlDefinitionLoader.builder(source).build().load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml -> lib.yml:3:5: import '../outside.yml': Definition identifier '../outside.yml' leaves the root"
        e.cause instanceof TransfluxValidationException
    }

    def 'imports are told apart by the identifier asked for, and named in errors by the one the source reports'() {
        given:
        Map<String, String> documents = [
            'root.yml'  : root('./lib.yml', 'lib.yml'),
            './lib.yml' : LIBRARY,
            'lib.yml'   : LIBRARY]
        DefinitionSource source = { String id ->
            opens[id]++
            return Optional.of(new DefinitionResource(id.replace('./', ''),
                new ByteArrayInputStream(documents[id].stripIndent().bytes)))
        }

        when:
        YamlDefinitionLoader.builder(source).build().load('root.yml', Order)

        then:
        opens['./lib.yml'] == 1
        opens['lib.yml'] == 1
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml -> lib.yml:3:9: step 'record': Action ID 'record' is already registered;" +
            ' first declared at lib.yml:3:9'
    }

    def 'an id registered twice names both declarations, whichever documents they sit in'() {
        when:
        loader(
            'root.yml': "apiVersion: transflux/v1\nimports:\n  - lib.yml\n${section}\nstateMachine:\n  entityType: ${Order.name}\n",
            'lib.yml' : "apiVersion: transflux/v1\n${section}\n")
            .load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.identifier() == 'root.yml'
        e.line() == line
        e.problem().endsWith('; first declared at lib.yml:3:9')

        where:
        kind        | section                                                                                                          || line
        'step'      | "steps:\n  - id: dup\n    class: ${RecordingStep.name}"                                                        || 5
        // A condition's rejection sits at its form, where core's own checks on it land too; and an
        // equal expression under one id is accepted by core, so the duplicate is a class.
        'condition' | "conditions:\n  - id: dup\n    class: ${PriorityCondition.name}"                                              || 6
        'mapper'    | "mappers:\n  - id: dup\n    parentType: ${Ctx.name}\n    childType: ${ChildCtx.name}\n    class: ${ChildMapper.name}" || 5
        'trigger'   | "triggers:\n  - id: dup\n    type: manual"                                                                     || 5
        'listener'  | "listeners:\n  - id: dup\n    class: ${StateAudit.name}"                                                        || 5
    }

    def 'the first declaration named is one of the same kind'() {
        when:
        // A mapper and a step do not collide until the build, so the first step is what the second collides with.
        loader(
            'root.yml': """\
                apiVersion: transflux/v1
                imports:
                  - lib.yml
                steps:
                  - id: x
                    class: ${RecordingStep.name}
                  - id: x
                    class: ${RecordingStep.name}
                stateMachine:
                  entityType: ${Order.name}
                """,
            'lib.yml' : "apiVersion: transflux/v1\nmappers:\n  - id: x\n    parentType: ${Ctx.name}\n    childType: ${ChildCtx.name}\n    class: ${ChildMapper.name}\n")
            .load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml:7:9: step 'x': Action ID 'x' is already registered; first declared at root.yml:5:9"
    }

    def 'a listener declared in place on a state or the state machine names a registration it collides with'() {
        when:
        loader(
            'root.yml': "apiVersion: transflux/v1\nimports:\n  - lib.yml\nstateMachine:\n  entityType: ${Order.name}\n" + owner,
            'lib.yml' : "apiVersion: transflux/v1\nlisteners:\n  - id: l\n    class: ${StateAudit.name}\n")
            .load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.identifier() == 'root.yml'
        e.problem() == "Listener ID 'l' is already registered; first declared at lib.yml:3:9"

        where:
        owner << [
            "  states:\n    - id: a\n      listeners:\n        onEntry:\n          - id: l\n            class: ${StateAudit.name}\n",
            "  listeners:\n    onAnyStateEntry:\n      - id: l\n        class: ${StateAudit.name}\n"]
    }

    def 'a state or transition declared twice in one document names both lines'() {
        when:
        loader('root.yml': """\
            apiVersion: transflux/v1
            stateMachine:
              entityType: ${Order.name}
              states:
                - id: a
                - id: ${state}
              transitions:
                - id: t
                  from: a
                  to: a
                - id: ${transition}
                  from: a
                  to: a
            """).load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message.startsWith("root.yml:${line}:")
        e.problem().endsWith("; first declared at root.yml:${first}")

        where:
        state | transition || line | first
        'a'   | 'u'        || 6    | '5:11'
        'b'   | 't'        || 11   | '8:11'
    }

    def 'an error deep in the imports carries every document between it and the root'() {
        when:
        loader(
            'root.yml': root('mid.yml'),
            'mid.yml' : imports('lib.yml'),
            'lib.yml' : "apiVersion: transflux/v1\nsteps:\n  - id: s\n    class: ${RecordingStep.name}\n    bogus: 1\n")
            .load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.importChain() == ['root.yml', 'mid.yml']
        e.identifier() == 'lib.yml'
        e.line() == 5
        e.message.startsWith("root.yml -> mid.yml -> lib.yml:5:5: step 's': unknown key 'bogus'")
    }

    private YamlDefinitionLoader loader(Map<String, String> documents) {
        DefinitionSource source = { String id ->
            opens[id]++
            String text = documents[id]
            return text == null
                ? Optional.empty()
                : Optional.of(new DefinitionResource(id, new ByteArrayInputStream(text.stripIndent().bytes)))
        }
        return YamlDefinitionLoader.builder(source).build()
    }

    private static StateMachine<Order> build(YamlDefinitionLoader loader) {
        return loader.load('root.yml', Order).build()
    }

    private static String imports(String... identifiers) {
        return 'apiVersion: transflux/v1\nimports:\n' + identifiers.collect { "  - ${it}\n" }.join('')
    }

    /**
     * A root importing {@code identifiers}, whose one transition runs, is guarded by and is fired
     * through what {@link #LIBRARY} registers.
     */
    private static String root(String... identifiers) {
        return imports(identifiers) + """\
            stateMachine:
              entityType: ${Order.name}
              stateResolver:
                expression: 'state'
              stateApplier:
                expression: 'state'
              states:
                - id: a
                - id: b
              transitions:
                - id: t
                  from: a
                  to: b
                  preConditions: [always]
                  triggers: [go]
                  actions:
                    - run: record
            """.stripIndent()
    }
}

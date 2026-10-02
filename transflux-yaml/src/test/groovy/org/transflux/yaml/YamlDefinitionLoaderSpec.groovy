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
import org.transflux.yaml.source.ClasspathDefinitionSource
import org.transflux.yaml.source.DefinitionResource
import org.transflux.yaml.source.DefinitionSource
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class YamlDefinitionLoaderSpec extends Specification {

    private static final String ENVELOPE = LoaderFixtures.resource('loader/envelope.transflux.yml')

    def 'an envelope loads into a definition for the entity type, which the host completes and builds'() {
        given:
        def def_ = loader(ENVELOPE).load('root.yml', Order)

        when:
        def sm = def_
            .withStateResolver { Order o -> o.state }
            .withStateApplier { Order o, String s -> o.state = s }
            .state('a')
            .transition('t', 'a', 'b', { })
            .state('b')
            .build()
        def order = new Order()

        then:
        sm.entity(order).transitionTo('b').success
        order.state == 'b'

        cleanup:
        sm?.close()
    }

    def 'reads through a real source'() {
        expect:
        YamlDefinitionLoader.builder(new ClasspathDefinitionSource()).build()
            .load('corpus/valid/loader/envelope.transflux.yml', Order) != null
    }

    def 'refuses #what'() {
        when:
        loader(text).load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == message

        where:
        what                     | text                                                                                || message
        'a list for a document'  | '- a\n'                                                                             || 'root.yml:1:1: a definition document must be a mapping'
        'a missing apiVersion'   | "stateMachine:\n  entityType: ${Order.name}\n"                                      || "root.yml:1:1: 'apiVersion' is required"
        'another apiVersion'     | "apiVersion: transflux/v2\nstateMachine:\n  entityType: ${Order.name}\n"            || "root.yml:1:13: 'apiVersion' must be 'transflux/v1', not 'transflux/v2'"
        'a missing stateMachine' | 'apiVersion: transflux/v1\n'                                                        || "root.yml:1:1: 'stateMachine' is required"
        'an unknown root key'    | "apiVersion: transflux/v1\nextra: 1\nstateMachine:\n  entityType: ${Order.name}\n"  || "root.yml:2:1: unknown key 'extra'; expected one of apiVersion, stateMachine, imports, steps, operations, choices, conditions, mappers, triggers, listeners"
        'a missing entityType'   | 'apiVersion: transflux/v1\nstateMachine:\n  other: 1\n'                             || "root.yml:3:3: state machine: 'entityType' is required"
        'an unloadable entity'   | 'apiVersion: transflux/v1\nstateMachine:\n  entityType: com.nope.Order\n'           || 'root.yml:3:15: state machine: class com.nope.Order cannot be loaded'
        'another entity'         | "apiVersion: transflux/v1\nstateMachine:\n  entityType: ${String.name}\n"           || "root.yml:3:15: state machine: entityType is java.lang.String, but ${Order.name} was asked for"
        'an unknown key inside'  | "apiVersion: transflux/v1\nstateMachine:\n  entityType: ${Order.name}\n  extra: {}\n" || "root.yml:4:3: state machine: unknown key 'extra'; expected one of entityType, name, description, id, version, stateResolver, stateApplier, listeners, config, states, transitions"
    }

    def 'a document is read as #encoding'() {
        given:
        def text = "apiVersion: transflux/v1\nstateMachine:\n  entityType: ${Order.name}\n  name: Café\n"
        def bytes = (bom + text.getBytes(charset).toList()) as byte[]
        def source = { String id -> Optional.of(new DefinitionResource(id, new ByteArrayInputStream(bytes))) }
            as DefinitionSource

        when:
        def sm = YamlDefinitionLoader.builder(source).build().load('root.yml', Order)
            .withStateResolver { 'a' }.state('a').build()

        then:
        sm.name == 'Café'

        cleanup:
        sm?.close()

        where:
        encoding              | charset                   | bom
        'UTF-8'               | StandardCharsets.UTF_8    | []
        'UTF-8 with a BOM'    | StandardCharsets.UTF_8    | [0xEF, 0xBB, 0xBF]
        'UTF-16LE with a BOM' | StandardCharsets.UTF_16LE | [0xFF, 0xFE]
        'UTF-16BE with a BOM' | StandardCharsets.UTF_16BE | [0xFE, 0xFF]
    }

    def 'a document in another encoding is refused rather than misread'() {
        given:
        def text = "apiVersion: transflux/v1\nstateMachine:\n  entityType: ${Order.name}\n  name: Café\n"
        def source = { String id -> Optional.of(new DefinitionResource(id, new ByteArrayInputStream(text.getBytes('windows-1252')))) }
            as DefinitionSource

        when:
        YamlDefinitionLoader.builder(source).build().load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == 'root.yml: the document is not valid UTF-8 or UTF-16 text; save it as UTF-8'
    }

    def 'a document over the code point limit is refused as such'() {
        when:
        YamlDefinitionLoader.builder(source(ENVELOPE)).withCodePointLimit(20).build().load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == ('root.yml: the document is over the limit of 20 code points;'
            + ' raise it with YamlDefinitionLoader.Builder.withCodePointLimit(int)')
    }

    def 'a document within a raised code point limit loads'() {
        expect:
        YamlDefinitionLoader.builder(source(ENVELOPE)).withCodePointLimit(ENVELOPE.length())
            .build().load('root.yml', Order) != null
    }

    def 'a code point limit must be positive: #limit'() {
        when:
        YamlDefinitionLoader.builder(source(ENVELOPE)).withCodePointLimit(limit)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Code point limit must be positive, was ${limit}"

        where:
        limit << [0, -1]
    }

    def "a document's expressions resolve types through the loader's class loader, whichever thread evaluates"() {
        given:
        def rules = YamlDefinitionLoaderSpec.name
        def definition = loader("""            apiVersion: transflux/v1
            stateMachine:
              entityType: ${Order.name}
              stateResolver:
                expression: "T(${rules}).stateOf(#entity)"
              stateApplier:
                expression: state
              states:
                - id: a
                - id: b
              transitions:
                - id: t
                  from: a
                  to: b
                  preConditions:
                    - expression: "T(${rules}).ok(#entity)"
            """.stripIndent()).load('root.yml', Order)
        def original = Thread.currentThread().contextClassLoader

        when: 'it is built and fired on a thread whose context class loader sees the JDK alone'
        Thread.currentThread().contextClassLoader = new URLClassLoader(new URL[0], (ClassLoader) null)
        def sm = definition.build()
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success

        cleanup:
        Thread.currentThread().contextClassLoader = original
        sm?.close()
    }

    def 'a document the source does not have is reported by its identifier'() {
        when:
        YamlDefinitionLoader.builder({ Optional.empty() } as DefinitionSource).build().load('missing.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == 'missing.yml: no such document'
        e.line() == null
    }

    def 'a source failing to open the document is reported by its identifier: #failure.class.simpleName'() {
        when:
        YamlDefinitionLoader.builder({ throw failure } as DefinitionSource).build().load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml: ${problem}"
        e.problem() == problem
        e.importChain() == []
        e.line() == null
        e.column() == null
        e.cause.is(failure)

        where:
        failure                                                || problem
        new TransfluxValidationException('identifier refused') || 'identifier refused'
        new UncheckedIOException(new IOException('disk gone')) || 'java.io.IOException: disk gone'
        new IllegalStateException()                            || 'java.lang.IllegalStateException'
    }

    def 'the same class from another class loader is named as such'() {
        given: 'a loader with no parent that reads the test classes, and Groovy, itself'
        def isolated = new URLClassLoader(
            [Order, GroovyObject].collect { it.protectionDomain.codeSource.location } as URL[], (ClassLoader) null)
        def loader = YamlDefinitionLoader.builder(source(ENVELOPE)).withClassLoader(isolated).build()

        when:
        loader.load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.problem().endsWith('(loaded by another class loader)')

        cleanup:
        isolated.close()
    }

    def 'without a class loader of its own, a loader uses the context class loader current when it was built'() {
        given:
        def thread = Thread.currentThread()
        def original = thread.contextClassLoader
        thread.contextClassLoader = new URLClassLoader(new URL[0], (ClassLoader) null)
        def blind = YamlDefinitionLoader.builder(source(ENVELOPE)).build()
        thread.contextClassLoader = original

        when:
        blind.load('root.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.problem() == "class ${Order.name} cannot be loaded"
    }

    def 'with no context class loader either, a loader falls back to its own'() {
        given:
        def thread = Thread.currentThread()
        def original = thread.contextClassLoader
        thread.contextClassLoader = null
        def loader
        try {
            loader = YamlDefinitionLoader.builder(source(ENVELOPE)).build()
        } finally {
            thread.contextClassLoader = original
        }

        expect:
        loader.load('root.yml', Order) != null
    }

    def 'a stream that fails to close costs a warning, not the load'() {
        given:
        def stream = new ByteArrayInputStream(ENVELOPE.bytes) {
            @Override
            void close() throws IOException {
                throw new IOException('stuck')
            }
        }
        def source = { String id -> Optional.of(new DefinitionResource(id, stream)) } as DefinitionSource

        expect:
        YamlDefinitionLoader.builder(source).build().load('root.yml', Order) != null
    }

    def 'the stream is closed whether the load succeeds or fails'() {
        given:
        def streams = []
        def source = { String id ->
            def stream = new TrackingStream(text.bytes)
            streams << stream
            Optional.of(new DefinitionResource(id, stream))
        } as DefinitionSource

        when:
        try {
            YamlDefinitionLoader.builder(source).build().load('root.yml', Order)
        } catch (DefinitionLoadException ignored) {
        }

        then:
        streams*.closed == [true]

        where:
        text << [ENVELOPE, 'apiVersion: [\n']
    }

    def 'every load reads through the source again'() {
        given:
        def opened = 0
        def source = { String id ->
            opened++
            Optional.of(new DefinitionResource(id, new ByteArrayInputStream(ENVELOPE.bytes)))
        } as DefinitionSource
        def loader = YamlDefinitionLoader.builder(source).build()

        when:
        loader.load('root.yml', Order)
        loader.load('root.yml', Order)

        then:
        opened == 2
    }

    def 'null and blank arguments are refused'() {
        when:
        call()

        then:
        thrown(TransfluxValidationException)

        where:
        call << [
            { YamlDefinitionLoader.builder(null) },
            { YamlDefinitionLoader.builder(source(ENVELOPE)).withClassLoader(null) },
            { YamlDefinitionLoader.builder(source(ENVELOPE)).withComponentFactory(null) },
            { YamlDefinitionLoader.builder(source(ENVELOPE)).build().load(' ', Order) },
            { YamlDefinitionLoader.builder(source(ENVELOPE)).build().load('root.yml', null) },
        ]
    }

    private static YamlDefinitionLoader loader(String text) {
        return YamlDefinitionLoader.builder(source(text)).build()
    }

    static String stateOf(Order order) {
        return order.state
    }

    static boolean ok(Order order) {
        return order != null
    }

    private static DefinitionSource source(String text) {
        return { String id -> Optional.of(new DefinitionResource(id, new ByteArrayInputStream(text.bytes))) }
            as DefinitionSource
    }

    static class Order {
        String state = 'a'
    }

    static class TrackingStream extends ByteArrayInputStream {
        boolean closed

        TrackingStream(byte[] bytes) {
            super(bytes)
        }

        @Override
        void close() {
            closed = true
            super.close()
        }
    }
}

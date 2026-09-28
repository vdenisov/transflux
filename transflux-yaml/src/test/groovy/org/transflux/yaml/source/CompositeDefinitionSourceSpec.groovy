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

package org.transflux.yaml.source

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CompositeDefinitionSourceSpec extends Specification {

    @TempDir
    Path dir

    /** Every identifier the fake sources were asked for, in order, as "source:identifier". */
    List<String> asked = []

    def 'an unprefixed identifier is asked of every source in order, and the first to find it wins'() {
        given: 'classpath, a config directory, the home directory and a database'
        def config = directory('config', ['definition.yaml': 'from config'])
        def home = directory('home', ['definition.yaml': 'from home', 'personal.yaml': 'from home'])
        def source = CompositeDefinitionSource.of(
            new ClasspathDefinitionSource(),
            new FileSystemDefinitionSource(config).withPrefixes('config:', 'file:'),
            new FileSystemDefinitionSource(home).withPrefixes('home:', 'file:'),
            fake('db', ['db:'], ['definition.yaml', 'only-in-db.yaml']))

        expect:
        read(source, 'definitions/sample.transflux.yml') == 'apiVersion: transflux/v1\n'
        read(source, 'definition.yaml') == 'from config'
        read(source, 'personal.yaml') == 'from home'
        read(source, 'only-in-db.yaml') == 'db:only-in-db.yaml'
        source.open('nowhere.yaml').empty
    }

    def 'a prefixed identifier is asked of the sources declaring the prefix, in order, with the prefix stripped'() {
        given:
        def source = CompositeDefinitionSource.of(
            fake('classpath', ['cp:'], ['definition.yaml']),
            fake('config', ['config:', 'file:'], ['definition.yaml']),
            fake('home', ['home:', 'file:'], ['definition.yaml', 'personal.yaml']))

        expect:
        read(source, 'file:definition.yaml') == 'config:definition.yaml'
        read(source, 'file:personal.yaml') == 'home:personal.yaml'
        read(source, 'home:definition.yaml') == 'home:definition.yaml'
        asked == ['config:definition.yaml', 'config:personal.yaml', 'home:personal.yaml', 'home:definition.yaml']
    }

    def 'a matched prefix whose sources all miss is a miss, the other sources unasked'() {
        given:
        def source = CompositeDefinitionSource.of(
            fake('classpath', ['cp:'], ['definition.yaml']),
            fake('db', ['db:'], []))

        expect:
        source.open('db:definition.yaml').empty
        asked == ['db:definition.yaml']
    }

    def 'an identifier starting with no declared prefix goes to every source verbatim'() {
        given:
        def source = CompositeDefinitionSource.of(
            fake('classpath', ['cp:'], []),
            fake('db', [], ['urn:x']))

        expect:
        read(source, 'urn:x') == 'db:urn:x'
        asked == ['classpath:urn:x', 'db:urn:x']
    }

    def 'each source strips the longest of its own prefixes'() {
        given:
        def source = CompositeDefinitionSource.of(
            fake('short', ['db:'], []),
            fake('long', ['db:', 'db://'], ['x']))

        expect:
        read(source, 'db://x') == 'long:x'
        asked == ['short://x', 'long:x']
    }

    def "the resource reports the whole identifier, and the answering source's location and metadata"() {
        given:
        def modified = Instant.parse('2026-01-01T00:00:00Z')
        DefinitionSource db = new DefinitionSource() {
            Optional<DefinitionResource> open(String id) {
                Optional.of(new DefinitionResource(id, stream('db'), 'workflows#' + id, modified, 'v7'))
            }

            Set<String> prefixes() { ['db://'] as Set }
        }

        when:
        def resource = CompositeDefinitionSource.of(db).open('db://subscription').get()

        then:
        resource.identifier() == 'db://subscription'
        resource.location() == 'workflows#subscription'
        resource.lastModified() == modified
        resource.etag() == 'v7'
    }

    def 'a failing source ends the search: a failure is not a miss'() {
        given:
        DefinitionSource failing = { id -> throw new UncheckedIOException(new IOException('down')) }
        def after = fake('after', [], ['x'])

        when:
        CompositeDefinitionSource.of(failing, after).open('x')

        then:
        thrown(UncheckedIOException)
        asked == []
    }

    def 'a lambda source declares no prefix and is reached by an unprefixed identifier only'() {
        given:
        DefinitionSource lambda = { id -> Optional.of(new DefinitionResource(id, stream('lambda'))) }
        def source = CompositeDefinitionSource.of(fake('cp', ['cp:'], []), lambda)

        expect:
        lambda.prefixes().empty
        read(source, 'x') == 'lambda'
        source.open('cp:x').empty
    }

    def 'rejects a malformed configuration'() {
        when:
        factory.call()

        then:
        thrown(TransfluxValidationException)

        where:
        factory << [
            { -> CompositeDefinitionSource.of() },
            { -> CompositeDefinitionSource.of((DefinitionSource[]) null) },
            { -> CompositeDefinitionSource.of(new ClasspathDefinitionSource(), null) },
            { -> CompositeDefinitionSource.of(new ClasspathDefinitionSource().withPrefixes('cp:', '')) }
        ]
    }

    def 'rejects a source declaring a blank prefix'() {
        when:
        CompositeDefinitionSource.of(fake('blank', [' '], []))

        then:
        thrown(TransfluxValidationException)
    }

    def 'rejects a blank identifier'() {
        when:
        CompositeDefinitionSource.of(new ClasspathDefinitionSource()).open(' ')

        then:
        thrown(TransfluxValidationException)
    }

    /** A source declaring {@code prefixes} that holds exactly {@code documents}, each reading "name:identifier". */
    private DefinitionSource fake(String name, List<String> prefixes, List<String> documents) {
        def log = asked
        return new DefinitionSource() {
            Optional<DefinitionResource> open(String id) {
                log << "$name:$id".toString()
                return documents.contains(id)
                    ? Optional.of(new DefinitionResource(id, stream("$name:$id")))
                    : Optional.<DefinitionResource> empty()
            }

            Set<String> prefixes() { prefixes as Set }
        }
    }

    private Path directory(String name, Map<String, String> files) {
        def root = Files.createDirectory(dir.resolve(name))
        files.each { file, text -> Files.writeString(root.resolve(file), text) }
        return root
    }

    private static String read(DefinitionSource source, String identifier) {
        return source.open(identifier).get().withCloseable { it.bytes().text }
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.bytes)
    }
}

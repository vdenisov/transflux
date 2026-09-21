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

import java.time.Instant

class DefinitionResourceSpec extends Specification {

    def 'reports what it was created with'() {
        given:
        def stream = new ByteArrayInputStream('x'.bytes)
        def modified = Instant.parse('2026-01-01T00:00:00Z')

        when:
        def resource = new DefinitionResource('a.yml', stream, '/defs/a.yml', modified, 'v1')

        then:
        resource.identifier() == 'a.yml'
        resource.bytes().is(stream)
        resource.location() == '/defs/a.yml'
        resource.lastModified() == modified
        resource.etag() == 'v1'
    }

    def 'location and change metadata are optional'() {
        when:
        def resource = new DefinitionResource('a.yml', new ByteArrayInputStream(new byte[0]))

        then:
        resource.location() == null
        resource.lastModified() == null
        resource.etag() == null
    }

    def 'rejects a missing identifier or stream'() {
        when:
        new DefinitionResource(identifier, stream)

        then:
        thrown(TransfluxValidationException)

        where:
        identifier | stream
        null       | new ByteArrayInputStream(new byte[0])
        ' '        | new ByteArrayInputStream(new byte[0])
        'a.yml'    | null
    }

    def 'closing it closes the stream'() {
        given:
        def closed = false
        def stream = new ByteArrayInputStream(new byte[0]) {
            @Override
            void close() { closed = true }
        }

        when:
        new DefinitionResource('a.yml', stream).close()

        then:
        closed
    }

    def 'a failing close surfaces unchecked, naming the resource'() {
        given:
        def stream = new ByteArrayInputStream(new byte[0]) {
            @Override
            void close() { throw new IOException('disk gone') }
        }

        when:
        new DefinitionResource('a.yml', stream).close()

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains("'a.yml'")
    }
}

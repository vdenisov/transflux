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

class ClasspathDefinitionSourceSpec extends Specification {

    def 'opens a classpath resource by its name'() {
        when:
        def resource = new ClasspathDefinitionSource().open('definitions/sample.transflux.yml')

        then:
        resource.present
        resource.get().identifier() == 'definitions/sample.transflux.yml'
        resource.get().withCloseable { it.bytes().text } == 'apiVersion: transflux/v1\n'
        resource.get().location().endsWith('definitions/sample.transflux.yml')
        resource.get().lastModified() == null
    }

    def "answers to 'cp:' unless given other prefixes"() {
        given:
        def source = new ClasspathDefinitionSource()

        expect:
        source.prefixes() == ['cp:'] as Set
        source.withPrefixes('classpath:', 'cp:').prefixes() == ['classpath:', 'cp:'] as Set
        source.withPrefixes().prefixes().empty
        source.withPrefixes('lib:').open('definitions/sample.transflux.yml').present
    }

    def 'a missing resource is empty'() {
        expect:
        new ClasspathDefinitionSource().open('definitions/missing.transflux.yml').empty
    }

    def 'reads through the class loader it was given'() {
        given: 'a loader that sees nothing'
        def loader = new URLClassLoader(new URL[0], (ClassLoader) null)

        expect:
        new ClasspathDefinitionSource(loader).open('definitions/sample.transflux.yml').empty
    }

    def 'a resource that exists but cannot be opened is a failure, not a miss'() {
        given: 'a loader naming a resource whose URL no longer opens'
        def loader = new ClassLoader(null) {
            @Override
            URL getResource(String name) { new File('does-not-exist/sample.transflux.yml').toURI().toURL() }
        }

        when:
        new ClasspathDefinitionSource(loader).open('definitions/sample.transflux.yml')

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains("'definitions/sample.transflux.yml'")
    }

    def 'rejects a blank identifier and a null class loader'() {
        when:
        new ClasspathDefinitionSource().open(' ')

        then:
        thrown(TransfluxValidationException)

        when:
        new ClasspathDefinitionSource(null)

        then:
        thrown(TransfluxValidationException)

        when:
        new ClasspathDefinitionSource().withPrefixes('cp:', ' ')

        then:
        thrown(TransfluxValidationException)
    }
}

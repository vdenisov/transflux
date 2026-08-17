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

package org.transflux.core.impl

import groovy.io.FileType
import spock.lang.Specification

import java.lang.reflect.Modifier

/**
 * The logger tree is public contract — it is the surface a host configures — so its shape is pinned
 * here rather than left to the README alone.
 */
class LoggersSpec extends Specification {

    /** Every declared leaf, read off the holder so a new constant cannot skip these rules. */
    private static List<String> leafNames() {
        return Loggers.declaredFields
            .findAll { Modifier.isStatic(it.modifiers) && org.slf4j.Logger.isAssignableFrom(it.type) }
            .collect { it.setAccessible(true); ((org.slf4j.Logger) it.get(null)).name }
    }

    def 'every leaf sits under the org.transflux root, so one name silences the library'() {
        expect:
        leafNames().every { it.startsWith('org.transflux.') }
    }

    def 'no leaf is an ancestor of another, so no line ever arrives from a grouping level'() {
        given: 'a name is either a grouping level or a logger, never both'
        def names = leafNames()

        expect:
        names.every { name ->
            names.every { other -> other == name || !other.startsWith(name + '.') }
        }
    }

    def 'leaf names are unique'() {
        given:
        def names = leafNames()

        expect:
        names.size() == names.toSet().size()
    }

    def 'the declared tree is exactly the documented one'() {
        given: 'yaml.parse and yaml.binding join when Phase 5 declares their first emitter'

        expect:
        leafNames().toSorted() == [
            'org.transflux.build.binding',
            'org.transflux.build.lifecycle',
            'org.transflux.build.registry',
            'org.transflux.build.validation',
            'org.transflux.execution.action',
            'org.transflux.execution.compensation',
            'org.transflux.execution.condition',
            'org.transflux.execution.listener',
            'org.transflux.execution.transition',
            'org.transflux.trigger'
        ]
    }

    def 'the holder is not instantiable'() {
        given: 'a holder of constants, never a thing to hold a reference to'
        def ctor = Loggers.getDeclaredConstructor()

        expect:
        Modifier.isPrivate(ctor.modifiers)
        Loggers.declaredConstructors.length == 1
    }

    def 'the framework declares no logger outside the holder'() {
        given: 'a class-derived logger would name a class a host cannot rely on across refactors'
        def sources = []
        new File('src/main/java/org/transflux').traverse(
            type: FileType.FILES, nameFilter: ~/.*\.java/) { sources << it }

        when:
        def offenders = sources.findAll { file ->
            file.name != 'Loggers.java' && file.text.contains('LoggerFactory.getLogger')
        }

        then: 'the sweep found something to sweep, so an empty result means clean rather than broken'
        sources.size() > 1
        offenders.collect { it.name } == []
    }
}

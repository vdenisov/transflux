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

import groovy.io.FileType
import org.slf4j.Logger
import spock.lang.Specification

import java.lang.reflect.Modifier

/**
 * This module's half of the logger-tree rules the core module's {@code LoggersSpec} enforces. The
 * module keeps one holder per package, both package-private, so they are reached by name.
 */
class LoggersSpec extends Specification {

    private static final List<String> HOLDERS = [
        'org.transflux.yaml.Loggers',
        'org.transflux.yaml.source.Loggers',
    ]

    def 'the declared tree is exactly the documented one'() {
        expect:
        leafNames().toSorted() == ['org.transflux.yaml.binding', 'org.transflux.yaml.parse', 'org.transflux.yaml.source']
    }

    def 'no leaf is an ancestor of another'() {
        given:
        def leaves = leafNames()

        expect:
        leaves.every { leaf -> leaves.every { other -> other == leaf || !other.startsWith(leaf + '.') } }
    }

    def 'holder #name is not instantiable'() {
        given:
        def holder = Class.forName(name)
        def ctor = holder.getDeclaredConstructor()

        expect:
        Modifier.isPrivate(ctor.modifiers)
        holder.declaredConstructors.length == 1

        where:
        name << HOLDERS
    }

    def 'the module declares no logger outside the holders'() {
        given:
        def sources = []
        new File('src/main/java/org/transflux').traverse(
            type: FileType.FILES, nameFilter: ~/.*\.java/) { sources << it }

        when:
        def offenders = sources.findAll { file ->
            def path = file.path.replace(File.separator, '/')
            !path.endsWith('yaml/Loggers.java') && !path.endsWith('yaml/source/Loggers.java')
                && file.text.contains('LoggerFactory.getLogger')
        }

        then: 'the sweep found something to sweep, so an empty result means clean rather than broken'
        sources.size() > 2
        offenders.collect { it.name } == []
    }

    private static List<String> leafNames() {
        return HOLDERS.collectMany { name ->
            Class.forName(name).declaredFields
                .findAll { Modifier.isStatic(it.modifiers) && Logger.isAssignableFrom(it.type) }
                .collect { it.setAccessible(true); ((Logger) it.get(null)).name }
        }
    }
}

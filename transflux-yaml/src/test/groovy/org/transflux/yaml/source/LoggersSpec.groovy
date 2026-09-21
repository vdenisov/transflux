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

import groovy.io.FileType
import spock.lang.Specification

import java.lang.reflect.Modifier

/** This module's half of the logger-tree rules the core module's {@code LoggersSpec} enforces. */
class LoggersSpec extends Specification {

    private static List<String> leafNames() {
        return Loggers.declaredFields
            .findAll { Modifier.isStatic(it.modifiers) && org.slf4j.Logger.isAssignableFrom(it.type) }
            .collect { it.setAccessible(true); ((org.slf4j.Logger) it.get(null)).name }
    }

    def 'the declared tree is exactly the documented one'() {
        expect:
        leafNames().toSorted() == ['org.transflux.yaml.source']
    }

    def 'the holder is not instantiable'() {
        given:
        def ctor = Loggers.getDeclaredConstructor()

        expect:
        Modifier.isPrivate(ctor.modifiers)
        Loggers.declaredConstructors.length == 1
    }

    def 'the module declares no logger outside the holder'() {
        given:
        def sources = []
        new File('src/main/java/org/transflux').traverse(
            type: FileType.FILES, nameFilter: ~/.*\.java/) { sources << it }

        when:
        def offenders = sources.findAll { file ->
            !file.path.replace(File.separator, '/').endsWith('yaml/source/Loggers.java')
                && file.text.contains('LoggerFactory.getLogger')
        }

        then: 'the sweep found something to sweep, so an empty result means clean rather than broken'
        sources.size() > 1
        offenders.collect { it.name } == []
    }
}

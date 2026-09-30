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
import org.transflux.yaml.source.FileSystemDefinitionSource
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.transflux.yaml.LoaderFixtures.*

/**
 * Reloading a YAML definition is loading it again through the source and handing the result to
 * {@link StateMachine#replaceDefinition}: the documents here live in a directory and are rewritten
 * between loads, as an operator would edit them.
 */
class YamlDefinitionLoaderHotSwapSpec extends Specification {

    @TempDir
    Path dir

    YamlDefinitionLoader loader

    StateMachine<Order> sm

    def setup() {
        TRAIL.clear()
        loader = YamlDefinitionLoader.builder(new FileSystemDefinitionSource(dir)).build()
        write(root('b'), library(RecordingStep))
        sm = loader.load('root.transflux.yml', Order).build()
    }

    def cleanup() {
        sm?.close()
    }

    def 'an edited definition is loaded again and replaces the running one'() {
        expect: 'the first version runs'
        sm.entity(new Order()).transitionTo('b').success

        when: 'both documents are edited: another target, and the library step swapped'
        write(root('c'), library(GenericStep))
        long generation = sm.replaceDefinition(loader.load('root.transflux.yml', Order))
        Order order = new Order()
        def result = sm.entity(order).transitionTo('c')

        then: 'the same handle runs the new version, the imported library re-read with it'
        generation == 2
        sm.generation() == 2
        result.success
        order.state == 'c'
        TRAIL == ['step', 'generic-step']
    }

    def 'an edit the loader refuses never reaches the running machine'() {
        when:
        Files.writeString(dir.resolve('root.transflux.yml'), root('c') + '  initialState: a\n')
        loader.load('root.transflux.yml', Order)

        then:
        def e = thrown(DefinitionLoadException)
        e.identifier() == 'root.transflux.yml'
        e.location() == dir.resolve('root.transflux.yml').toRealPath().toString()
        e.problem().startsWith("unknown key 'initialState'")

        and: 'the first version is still the one running'
        sm.generation() == 1
        sm.entity(new Order()).transitionTo('b').success
    }

    def 'an edit that loads but does not build leaves the running machine as it was'() {
        given:
        Files.writeString(dir.resolve('root.transflux.yml'), root('c').replace('run: mark', 'run: nothing'))

        when:
        sm.replaceDefinition(loader.load('root.transflux.yml', Order))

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "transition 't' references unknown action id 'nothing' in its scope"

        and:
        sm.generation() == 1
        sm.entity(new Order()).transitionTo('b').success
        TRAIL == ['step']
    }

    private void write(String root, String library) {
        Files.writeString(dir.resolve('root.transflux.yml'), root)
        Files.writeString(dir.resolve('lib.transflux.yml'), library)
    }

    /**
     * @param target where the one transition leads from {@code a}
     *
     * @return a root document importing the library and running its step
     */
    private static String root(String target) {
        return """\
            apiVersion: transflux/v1
            imports:
              - lib.transflux.yml
            stateMachine:
              entityType: ${Order.name}
              stateResolver:
                expression: state
              stateApplier:
                expression: state
              states:
                - id: a
                - id: ${target}
              transitions:
                - id: t
                  from: a
                  to: ${target}
                  actions:
                    - run: mark
            """.stripIndent()
    }

    /**
     * @param step the class the library's {@code mark} step is
     *
     * @return a library declaring it
     */
    private static String library(Class<?> step) {
        return "apiVersion: transflux/v1\nsteps:\n  - id: mark\n    class: ${step.name}\n"
    }
}

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

import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Function

class IdentifiedDefImplSpec extends Specification {

    def 'constructor should reject null id with the supplied id label'() {
        when:
        new TestDef(null)

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("Test ID")
    }

    def 'constructor should reject blank id with the supplied id label'() {
        when:
        new TestDef("   ")

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("Test ID")
    }

    def 'getId should return the constructor id'() {
        expect:
        new TestDef("foo").getId() == "foo"
    }

    def 'withName should round-trip and return self for fluent chaining'() {
        given:
        def def_ = new TestDef("foo")
        def_.beginConfigurer()

        when:
        def returned = def_.withName("My Name")

        then:
        returned.is(def_)
        def_.getName() == "My Name"
    }

    def 'withDescription should round-trip and return self for fluent chaining'() {
        given:
        def def_ = new TestDef("foo")
        def_.beginConfigurer()

        when:
        def returned = def_.withDescription("the description")

        then:
        returned.is(def_)
        def_.getDescription() == "the description"
    }

    def 'second withName call should overwrite and warn'() {
        given:
        def def_ = new TestDef("foo")
        def_.beginConfigurer()

        when:
        def_.withName("first")
        def_.withName("second")

        then:
        def_.getName() == "second"
    }

    def 'second withDescription call should overwrite and warn'() {
        given:
        def def_ = new TestDef("foo")
        def_.beginConfigurer()

        when:
        def_.withDescription("first")
        def_.withDescription("second")

        then:
        def_.getDescription() == "second"
    }

    def 'withName outside configurer-active window should throw with defLabel substituted'() {
        given:
        def def_ = new TestDef("foo")

        when:
        def_.withName("name")

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("test 'foo'")
        ex.message.contains("'withName'")
    }

    def 'withDescription outside configurer-active window should throw with defLabel substituted'() {
        given:
        def def_ = new TestDef("foo")

        when:
        def_.withDescription("description")

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("test 'foo'")
        ex.message.contains("'withDescription'")
    }

    @Unroll
    def 'concrete subclass #subclass rejects blank id with its own idLabel'() {
        when:
        factory.apply('   ')

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains(expectedIdLabel)

        where:
        subclass            | factory                                                                                                || expectedIdLabel
        'StateDefImpl'      | ({ id -> new StateDefImpl(Transflux.defineStateMachine() as StateMachineDefImpl, id) } as Function) || 'State ID'
        'TransitionDefImpl' | ({ id -> new TransitionDefImpl(id, 'src', 'tgt') } as Function)                                     || 'Transition ID'
    }

    @Unroll
    def 'concrete subclass #subclass surfaces its own kind in defLabel-driven guard messages'() {
        given:
        def def_ = factory.apply('my-id') as IdentifiedDefImpl

        when: 'mutation outside the configurer-active window'
        def_.withName('x')

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("${expectedKind} 'my-id'")
        ex.message.contains("'withName'")

        where:
        subclass            | factory                                                                                                || expectedKind
        'StateDefImpl'      | ({ id -> new StateDefImpl(Transflux.defineStateMachine() as StateMachineDefImpl, id) } as Function) || 'state'
        'TransitionDefImpl' | ({ id -> new TransitionDefImpl(id, 'src', 'tgt') } as Function)                                     || 'transition'
    }

    @Unroll
    def 'concrete subclass #subclass round-trips withName/withDescription via the inherited base'() {
        given:
        def def_ = factory.apply('my-id') as IdentifiedDefImpl
        def_.beginConfigurer()

        when:
        def_.withName('the name')
        def_.withDescription('the description')

        then:
        def_.getName() == 'the name'
        def_.getDescription() == 'the description'

        where:
        subclass             | factory
        'StateDefImpl'       | ({ id -> new StateDefImpl(Transflux.defineStateMachine() as StateMachineDefImpl, id) } as Function)
        'TransitionDefImpl'  | ({ id -> new TransitionDefImpl(id, 'src', 'tgt') } as Function)
    }

    private static class TestDef extends IdentifiedDefImpl<TestDef> {
        TestDef(String id) {
            super(id, "test", "Test ID")
        }
    }
}

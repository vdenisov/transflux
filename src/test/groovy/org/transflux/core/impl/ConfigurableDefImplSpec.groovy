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
//file:noinspection GroovyPointlessBoolean

package org.transflux.core.impl

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class ConfigurableDefImplSpec extends Specification {

    def 'requireConfigurerActive should throw before beginConfigurer'() {
        given:
        def def_ = new IdLessDef("foo")

        when:
        def_.callMutator()

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("foo-scope")
        ex.message.contains("'doStuff'")
    }

    def 'requireConfigurerActive should not throw between begin and end'() {
        given:
        def def_ = new IdLessDef("foo")
        def_.beginConfigurer()

        when:
        def_.callMutator()

        then:
        noExceptionThrown()
    }

    def 'requireConfigurerActive should throw again after endConfigurer'() {
        given:
        def def_ = new IdLessDef("foo")
        def_.beginConfigurer()
        def_.endConfigurer()

        when:
        def_.callMutator()

        then:
        thrown(TransfluxValidationException)
    }

    def 'runConfigurer should set the flag for the duration of the lambda and clear on normal return'() {
        given:
        def def_ = new IdLessDef("foo")
        def insideFlag = null

        when:
        ConfigurableDefImpl.runConfigurer(def_, { d ->
            insideFlag = d.isActive()
        })

        then:
        insideFlag == true
        !def_.isActive()
    }

    def 'runConfigurer should clear the flag even when the lambda throws'() {
        given:
        def def_ = new IdLessDef("foo")

        when:
        ConfigurableDefImpl.runConfigurer(def_, { d -> throw new RuntimeException("boom") })

        then:
        thrown(RuntimeException)
        !def_.isActive()
    }

    def 'id-bearing subclass should embed defKind and id into the guard message'() {
        given:
        def def_ = new TestIdDef("bar")

        when:
        def_.withName("name")

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("test 'bar'")
    }

    def 'id-less subclass should embed its own defLabel into the guard message'() {
        given:
        def def_ = new IdLessDef("baz")

        when:
        def_.callMutator()

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("baz-scope")
    }

    private static class IdLessDef extends ConfigurableDefImpl {
        private final String label

        IdLessDef(String label) {
            this.label = label
        }

        @Override
        protected String defLabel() {
            return label + "-scope"
        }

        void callMutator() {
            requireConfigurerActive("doStuff")
        }

        boolean isActive() {
            try {
                requireConfigurerActive("probe")
                return true
            } catch (TransfluxValidationException ignored) {
                return false
            }
        }
    }

    private static class TestIdDef extends IdentifiedDefImpl<TestIdDef> {
        TestIdDef(String id) {
            super(id, "test", "Test ID")
        }
    }
}

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

package org.transflux.core

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class ComponentFactorySpec extends Specification {

    def 'the reflective factory invokes the public no-argument constructor'() {
        expect:
        ComponentFactory.reflective().create(Plain) instanceof Plain
    }

    def 'the reflective factory refuses #reason'() {
        when:
        ComponentFactory.reflective().create(type)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Cannot instantiate ${type.name}: ${reason}"

        where:
        type          || reason
        Abstract      || 'it is abstract; name a concrete class'
        Runnable      || 'it is abstract; name a concrete class'
        NeedsArgument || 'it declares no no-argument constructor'
        Hidden        || 'its no-argument constructor is not accessible; make the class and the constructor public'
    }

    def 'a throwing constructor is reported by its exception class, and chained'() {
        when:
        ComponentFactory.reflective().create(Throwing)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Cannot instantiate ${Throwing.name}: its constructor threw java.lang.IllegalStateException"
        e.cause instanceof IllegalStateException
    }

    def 'a failing static initialiser is reported, on the first attempt and on a retry'() {
        when:
        ComponentFactory.reflective().create(FailingInitializer)

        then:
        def first = thrown(TransfluxValidationException)
        first.message == "Cannot instantiate ${FailingInitializer.name}: its static initialisation failed with java.lang.IllegalStateException"

        when:
        ComponentFactory.reflective().create(FailingInitializer)

        then: 'the JVM keeps a record of the first failure rather than the failure itself'
        def retry = thrown(TransfluxValidationException)
        retry.message == "Cannot instantiate ${FailingInitializer.name}: its static initialisation failed with java.lang.ExceptionInInitializerError"
    }

    def 'a VirtualMachineError from a constructor propagates untouched'() {
        when:
        ComponentFactory.reflective().create(Exhausted)

        then:
        def e = thrown(OutOfMemoryError)
        e.message == 'constructor'
    }

    static class Plain {
    }

    static abstract class Abstract {
    }

    static class NeedsArgument {
        NeedsArgument(String ignored) {
        }
    }

    static class Hidden {
        private Hidden() {
        }
    }

    static class FailingInitializer {
        static {
            if (true) {
                throw new IllegalStateException('initialised')
            }
        }
    }

    static class Exhausted {
        Exhausted() {
            throw new OutOfMemoryError('constructor')
        }
    }

    static class Throwing {
        Throwing() {
            throw new IllegalStateException('secret payload')
        }
    }
}

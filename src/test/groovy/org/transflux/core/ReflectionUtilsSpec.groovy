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

import spock.lang.Specification

import java.lang.reflect.InvocationTargetException

class ReflectionUtilsSpec extends Specification {

    static class HasNoArgCtor {
        String tag = 'made-it'
    }

    static class NeedsArgsCtor {
        NeedsArgsCtor(String required) {}
    }

    static class ThrowsInCtor {
        ThrowsInCtor() { throw new RuntimeException('blow up') }
    }

    def 'instantiateNoArg should produce an instance via the no-arg constructor'() {
        when:
        def instance = ReflectionUtils.instantiateNoArg(HasNoArgCtor, 'Widget')

        then:
        instance instanceof HasNoArgCtor
        instance.tag == 'made-it'
    }

    def 'instantiateNoArg should reject a class that has no no-arg constructor'() {
        when:
        ReflectionUtils.instantiateNoArg(NeedsArgsCtor, 'Widget')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Widget class '")
        e.message.endsWith("' has no accessible no-arg constructor")
        e.cause instanceof NoSuchMethodException
    }

    def 'instantiateNoArg should wrap an exception thrown from inside the constructor'() {
        when:
        ReflectionUtils.instantiateNoArg(ThrowsInCtor, 'Widget')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Failed to instantiate widget class '")
        e.cause instanceof InvocationTargetException
    }

    def 'instantiateNoArg should lower-case the type name in the instantiation-failed message'() {
        when:
        ReflectionUtils.instantiateNoArg(ThrowsInCtor, 'Operation')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Failed to instantiate operation class '")
    }
}

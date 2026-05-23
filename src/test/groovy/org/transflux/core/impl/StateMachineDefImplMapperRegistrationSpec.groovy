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

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.ContextMapper
import spock.lang.Specification

import java.util.function.Function

class StateMachineDefImplMapperRegistrationSpec extends Specification {

    static class Entity { }

    static class P {
        String value
    }

    static class N {
        String value
    }

    static class PNMapper implements ContextMapper<P, N> {
        @Override
        N mapTo(P p) {
            def n = new N()
            n.value = p.value
            return n
        }
    }

    def 'mapper(id, P, N, instance) registers a mapper retrievable by id'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        def mapper = new PNMapper()

        when:
        smd.mapper('p-to-n', P, N, mapper)

        then:
        smd.getMapperDef('p-to-n') != null
        smd.getMapperDef('p-to-n').parentType() == P
        smd.getMapperDef('p-to-n').childType() == N
        ((MapperDefImpl) smd.getMapperDef('p-to-n')).buildMapper().is(mapper)
    }

    def 'mapper(id, P, N, class) registers a class-form mapper and instantiates it on demand'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()

        when:
        smd.mapper('p-to-n', P, N, PNMapper)

        then:
        smd.getMapperDef('p-to-n') != null

        when:
        def built = ((MapperDefImpl) smd.getMapperDef('p-to-n')).buildMapper()

        then:
        built instanceof PNMapper
    }

    def 'mapper(id, P, N, Function) wraps the function in a ContextMapper with default no-op mapFrom'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        Function<P, N> fn = { P p ->
            def n = new N()
            n.value = p.value
            return n
        }

        when:
        smd.mapper('p-to-n', P, N, fn)

        then:
        def mapperDef = smd.getMapperDef('p-to-n')
        mapperDef != null
        def built = ((MapperDefImpl) mapperDef).buildMapper()
        def parent = new P(value: 'hello')
        def child = built.mapTo(parent)
        child instanceof N
        child.value == 'hello'

        when:
        built.mapFrom(parent, child)   // default no-op

        then:
        notThrown(Exception)
    }

    def 'registering two mappers with the same id rejects'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.mapper('p-to-n', P, N, new PNMapper())

        when:
        smd.mapper('p-to-n', P, N, PNMapper)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'p-to-n'")
    }

    def 'null mapper instance is rejected'() {
        when:
        new StateMachineDefImpl<Entity>().mapper('x', P, N, (ContextMapper<P, N>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'null mapper class is rejected'() {
        when:
        new StateMachineDefImpl<Entity>().mapper('x', P, N, (Class<? extends ContextMapper<P, N>>) null)

        then:
        thrown(TransfluxValidationException)
    }

    def 'null function is rejected'() {
        when:
        new StateMachineDefImpl<Entity>().mapper('x', P, N, (Function<P, N>) null)

        then:
        thrown(TransfluxValidationException)
    }
}

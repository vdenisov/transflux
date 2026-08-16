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

import org.transflux.core.Identifiable
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.MapperDef
import spock.lang.Specification

import java.util.function.Consumer

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

    def 'a lambda registers the read-only form, leaving mapFrom the default no-op'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        ContextMapper<P, N> fn = { P p ->
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

    def 'null mapper configurer is rejected'() {
        when:
        new StateMachineDefImpl<Entity>().mapperDef('x', P, N, (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Mapper configurer cannot be null'
    }

    def 'mapperDef registers through a configurer and carries the metadata'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        def instance = new PNMapper()

        when:
        smd.mapperDef('p-to-n', P, N, { MapperDef d ->
            d.withName('P to N').withDescription('projects value').using(instance)
        } as Consumer)

        then:
        def mapperDef = smd.getMapperDef('p-to-n')
        mapperDef.getName() == 'P to N'
        mapperDef.getDescription() == 'projects value'
        ((MapperDefImpl) mapperDef).buildMapper().is(instance)
    }

    def 'a mapper def is inert once its configurer has returned'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        def captured = null
        smd.mapperDef('p-to-n', P, N, { MapperDef d ->
            captured = d
            d.using(new PNMapper())
        } as Consumer)

        when:
        captured.withName('too late')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("mapper 'p-to-n'")
        e.message.contains('after its configurer has returned')
    }

    def 'mapperDef accepts an Identifiable id'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()

        when:
        smd.mapperDef({ -> 'p-to-n' } as Identifiable, P, N,
                      { MapperDef d -> d.using(new PNMapper()) } as Consumer)

        then:
        smd.getMapperDef('p-to-n') != null
    }

    def 'mapperDef without using(...) fails at build with a message naming the fix'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.mapperDef('p-to-n', P, N, { MapperDef d -> d.withName('nameless') } as Consumer)

        when:
        ((MapperDefImpl) smd.getMapperDef('p-to-n')).buildMapper()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("MapperDef 'p-to-n'")
        e.message.contains('using(...)')
    }
}

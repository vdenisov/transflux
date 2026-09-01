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

package org.transflux.core.action

import spock.lang.Specification

import java.lang.reflect.ParameterizedType

/**
 * The member grammar is public contract: three positions hold an ordered action list, and a host
 * writing one should not have to remember which subset that position happens to admit. Nothing
 * else notices when the three drift apart, so the shape is pinned here.
 */
class ActionSequenceSpec extends Specification {

    /** The three positions that hold an ordered list of actions. */
    private static final List<Class<?>> SEQUENCES = [OperationDef, BranchDef, DefaultBranchDef]

    def '#type binds ActionSequence to itself, so a chain keeps its concrete type'() {
        when: 'the ActionSequence among the type\'s generic interfaces'
        def bound = type.genericInterfaces
            .findAll { it instanceof ParameterizedType }
            .find { ((ParameterizedType) it).rawType == ActionSequence }

        then: 'its SELF argument is the subtype itself, not the base'
        bound != null
        ((ParameterizedType) ((ParameterizedType) bound).actualTypeArguments[2]).rawType == type

        where:
        type << SEQUENCES
    }

    def 'the declared member grammar is exactly the documented one'() {
        expect: 'a widening is a deliberate edit here, not a side effect elsewhere'
        signatures(ActionSequence).toSorted() == [
            'conditional(java.lang.String,java.lang.Class,java.util.function.Consumer)',
            'conditional(java.lang.String,java.lang.Class,org.transflux.core.action.ContextMapper,java.util.function.Consumer)',
            'conditional(java.lang.String,java.util.function.Consumer)',
            'fork(java.lang.String)',
            'fork(java.lang.String,java.lang.String)',
            'fork(java.lang.String,org.transflux.core.action.ContextMapper)',
            'operation(java.lang.String,java.lang.Class,java.util.function.Consumer)',
            'operation(java.lang.String,java.lang.Class,org.transflux.core.action.ContextMapper,java.util.function.Consumer)',
            'operation(java.lang.String,java.util.function.Consumer)',
            'run(java.lang.String)',
            'run(java.lang.String,java.lang.String)',
            'run(java.lang.String,org.transflux.core.action.ContextMapper)',
            'step(java.lang.String,java.lang.Class)',
            'step(java.lang.String,java.lang.Class,java.lang.Class)',
            'step(java.lang.String,java.lang.Class,java.util.function.Consumer)',
            'step(java.lang.String,java.lang.Class,org.transflux.core.action.Action)',
            'step(java.lang.String,java.lang.Class,org.transflux.core.action.ContextMapper,java.lang.Class)',
            'step(java.lang.String,java.lang.Class,org.transflux.core.action.ContextMapper,java.util.function.Consumer)',
            'step(java.lang.String,java.lang.Class,org.transflux.core.action.ContextMapper,org.transflux.core.action.Action)',
            'step(java.lang.String,java.util.function.Consumer)',
            'step(java.lang.String,org.transflux.core.action.Action)',
        ]
    }

    def '#type offers every member form'() {
        expect:
        signatures(type).containsAll(signatures(ActionSequence))

        where:
        type << SEQUENCES
    }

    def '#type declares no member form of its own'() {
        expect: 'the grammar lives in exactly one place, so the three cannot drift'
        type.declaredMethods*.name.toSet()
            .intersect(['run', 'fork', 'step', 'conditional', 'operation'].toSet()).isEmpty()

        where:
        type << SEQUENCES
    }

    private static Set<String> signatures(Class<?> type) {
        return type.methods
            .collect { "${it.name}(${it.parameterTypes*.name.join(',')})".toString() }
            .toSet()
    }
}

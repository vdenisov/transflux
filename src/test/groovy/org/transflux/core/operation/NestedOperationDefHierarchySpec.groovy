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

package org.transflux.core.operation

import spock.lang.Specification

class NestedOperationDefHierarchySpec extends Specification {

    static class Entity { }

    static class P { }

    static class N { }

    def 'NestedOperationDef instances are assignable to OperationDef'() {
        given:
        NestedOperationDef<Entity, P, N> nested = new NestedOperationDefImpl<Entity, P, N>('nested')

        expect:
        nested instanceof OperationDef

        when:
        OperationDef<Entity, N> upcast = nested

        then:
        upcast.getId() == 'nested'
    }

    def 'withName covariant return preserves NestedOperationDef typing through the chain'() {
        given:
        def nested = new NestedOperationDefImpl<Entity, P, N>('nested')

        when:
        NestedOperationDef<Entity, P, N> chained = nested
            .withName('display-name')
            .withDescription('helpful description')

        then:
        chained.getId() == 'nested'
        chained.getName() == 'display-name'
        chained.getDescription() == 'helpful description'
    }

    def 'OperationDef-shaped accessors expose the metadata set on the NestedOperationDef'() {
        given:
        def nested = new NestedOperationDefImpl<Entity, P, N>('nested')
            .withName('the-name')
            .withDescription('the-description')

        when:
        OperationDef<Entity, N> viewed = nested

        then:
        viewed.getId() == 'nested'
        viewed.getName() == 'the-name'
        viewed.getDescription() == 'the-description'
    }
}

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

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.Transition
import spock.lang.Specification

class NestedOperationBuilderValidationSpec extends Specification {

    static class Entity { }

    static class P { }

    static class N { }

    static class NoOp implements Operation<Entity, N> {
        @Override
        void execute(Entity entity, N context, Transition<Entity, N> transition) { }
    }

    static class PNMapper implements ContextMapper<P, N> {
        @Override
        N mapTo(P p) { new N() }
    }

    def 'mixing withContextMapping(class) with inline mapTo is rejected at build'() {
        given:
        def composite = new CompositeOperationDefImpl<Entity, P>('outer')

        when:
        composite.operation('nested', NoOp, { NestedOperationDef<Entity, P, P> op ->
            op.usingContext(N)
                .withContextMapping(PNMapper)
                .mapTo({ P p -> new N() })
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('nested')
        e.message.contains('withContextMapping')
        e.message.contains('mapTo')
    }

    def 'mixing withContextMapping(instance) with inline mapFrom is rejected at build'() {
        given:
        def composite = new CompositeOperationDefImpl<Entity, P>('outer')

        when:
        composite.operation('nested', NoOp, { NestedOperationDef<Entity, P, P> op ->
            op.usingContext(N)
                .withContextMapping(new PNMapper())
                .mapFrom({ P p, N n -> })
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('nested')
    }

    def 'usingContext without mapTo or withContextMapping is rejected at build'() {
        given:
        def composite = new CompositeOperationDefImpl<Entity, P>('outer')

        when:
        composite.operation('nested', NoOp, { NestedOperationDef<Entity, P, P> op ->
            op.usingContext(N)
            // no mapTo, no withContextMapping
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('nested')
        e.message.contains('mapTo')
        e.message.contains('withContextMapping')
    }

    def 'no usingContext + no mapping = pass-through, accepted'() {
        given:
        def composite = new CompositeOperationDefImpl<Entity, P>('outer')

        when:
        composite.operation('nested', NoOp, { NestedOperationDef<Entity, P, P> op ->
            op.withName('plain-pass-through')
        })

        then:
        notThrown(TransfluxValidationException)
    }

    def 'declaring withContextMapping after a prior class mapper rejects with a clear message'() {
        given:
        def composite = new CompositeOperationDefImpl<Entity, P>('outer')

        when:
        composite.operation('nested', NoOp, { NestedOperationDef<Entity, P, P> op ->
            op.usingContext(N)
                .withContextMapping(PNMapper)
                .withContextMapping(new PNMapper())     // instance after class
        })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('nested')
    }
}

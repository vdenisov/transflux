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
import spock.lang.Specification
import spock.lang.Unroll

class CompositeOperationDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    private CompositeOperationDefImpl<Object, Object> composite

    void setup() {
        composite = new CompositeOperationDefImpl<>('outer')
    }

    @Unroll
    def 'step #variant accepts Identifiable refs'() {
        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                       | action
        'step(Identifiable)'                          | { c -> c.step(id('my-step')) }
        'step(Identifiable, Identifiable)'            | { c -> c.step(id('my-step'), id('my-mapper')) }
        'step(Identifiable, String mapperId)'         | { c -> c.step(id('my-step'), 'my-mapper') }
        'step(String stepId, Identifiable mapper)'    | { c -> c.step('my-step', id('my-mapper')) }
    }

    @Unroll
    def 'operation #variant accepts Identifiable refs'() {
        when:
        action.call(composite)

        then:
        composite.actionRefs.size() == 1

        where:
        variant                                           | action
        'operation(Identifiable)'                         | { c -> c.operation(id('my-op')) }
        'operation(Identifiable, Identifiable)'           | { c -> c.operation(id('my-op'), id('my-mapper')) }
        'operation(Identifiable, String mapperId)'        | { c -> c.operation(id('my-op'), 'my-mapper') }
        'operation(String opId, Identifiable mapper)'     | { c -> c.operation('my-op', id('my-mapper')) }
    }

    def 'Identifiable overloads accept any Identifiable (e.g. a held-onto *Def reference)'() {
        given:
        // Any def that extends Identifiable can be passed verbatim.
        def heldDef = new TransitionDefImpl<Object, Object>('held-id', 's1', 's2')

        when:
        composite.step(heldDef)

        then:
        composite.actionRefs.size() == 1
        composite.actionRefs[0].id() == 'held-id'
    }

    @Unroll
    def '#variant rejects null Identifiable arg'() {
        when:
        action.call(composite)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                       | action
        'step(null)'                                  | { c -> c.step((Identifiable) null) }
        'step(null, identifiable)'                    | { c -> c.step((Identifiable) null, id('m')) }
        'step(null, mapperId)'                        | { c -> c.step((Identifiable) null, 'm') }
        'step(identifiable, null)'                    | { c -> c.step(id('s'), (Identifiable) null) }
        'step(stepId, null)'                          | { c -> c.step('s', (Identifiable) null) }
        'operation(null)'                             | { c -> c.operation((Identifiable) null) }
        'operation(null, identifiable)'               | { c -> c.operation((Identifiable) null, id('m')) }
        'operation(null, mapperId)'                   | { c -> c.operation((Identifiable) null, 'm') }
        'operation(identifiable, null)'               | { c -> c.operation(id('o'), (Identifiable) null) }
        'operation(opId, null)'                       | { c -> c.operation('o', (Identifiable) null) }
    }
}

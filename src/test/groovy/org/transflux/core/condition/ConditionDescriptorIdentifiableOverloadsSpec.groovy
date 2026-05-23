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

package org.transflux.core.condition

import org.transflux.core.Identifiable
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class ConditionDescriptorIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    def 'ref(Identifiable) builds the same Reference descriptor as ref(String)'() {
        when:
        def fromIdentifiable = ConditionDescriptor.ref(id('cond-1'))
        def fromString = ConditionDescriptor.ref('cond-1')

        then:
        fromIdentifiable.id() == 'cond-1'
        fromIdentifiable.class == fromString.class
        fromIdentifiable == fromString
    }

    def 'ref(Identifiable) rejects null'() {
        when:
        ConditionDescriptor.ref((Identifiable) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('identifiable')
    }
}

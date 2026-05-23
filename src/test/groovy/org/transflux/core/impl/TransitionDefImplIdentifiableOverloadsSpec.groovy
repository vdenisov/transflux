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

class TransitionDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    private TransitionDefImpl<Object, Object> td

    void setup() {
        td = new TransitionDefImpl<>('t1', 's1', 's2')
        td.beginConfigurer()
    }

    def 'step(Identifiable) delegates to step(String)'() {
        when:
        td.step(id('my-step'))

        then:
        td.getOperationDef() != null
    }

    def 'preCondition(Identifiable) delegates to preCondition(String)'() {
        when:
        td.preCondition(id('my-cond'))

        then:
        !td.preConditionDescriptors.isEmpty()
        td.preConditionDescriptors[0].id() == 'my-cond'
    }

    def 'postCondition(Identifiable) delegates to postCondition(String)'() {
        when:
        td.postCondition(id('my-cond'))

        then:
        !td.postConditionDescriptors.isEmpty()
        td.postConditionDescriptors[0].id() == 'my-cond'
    }

    def 'all Identifiable overloads reject null'() {
        when:
        td."$method"(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('identifiable')

        where:
        method << ['step', 'preCondition', 'postCondition']
    }
}

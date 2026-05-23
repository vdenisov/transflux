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

class ConditionalStepDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    def 'branch(Identifiable, Consumer) registers a branch under the identifiable id'() {
        given:
        def cond = new ConditionalStepDefImpl<Object, Object>('c1')

        when:
        cond.branch(id('b1'), { b -> b.condition('any'); b.step('x') })

        then:
        cond.branches.size() == 1
        cond.branches[0].branchId == 'b1'
    }

    def 'branch(Identifiable, Consumer) rejects null Identifiable'() {
        given:
        def cond = new ConditionalStepDefImpl<Object, Object>('c1')

        when:
        cond.branch((Identifiable) null, { b -> })

        then:
        thrown(TransfluxValidationException)
    }
}

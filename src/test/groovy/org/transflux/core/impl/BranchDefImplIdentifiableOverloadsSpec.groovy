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

class BranchDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    def 'BranchDef.condition(Identifiable) sets a reference descriptor with the given id'() {
        given:
        def branch = new BranchDefImpl<Object, Object>('b1')

        when:
        branch.condition(id('my-cond'))

        then:
        branch.descriptor != null
        branch.descriptor.id() == 'my-cond'
    }

    def 'BranchDef.step(Identifiable) appends a by-id reference'() {
        given:
        def branch = new BranchDefImpl<Object, Object>('b1')

        when:
        branch.step(id('my-step'))

        then:
        branch.actionRefs.size() == 1
        branch.actionRefs[0].id() == 'my-step'
    }

    def 'DefaultBranchDef.step(Identifiable) appends a by-id reference'() {
        given:
        def defaultBranch = new DefaultBranchDefImpl<Object, Object>()

        when:
        defaultBranch.step(id('my-step'))

        then:
        defaultBranch.actionRefs.size() == 1
        defaultBranch.actionRefs[0].id() == 'my-step'
    }

    def 'BranchDef Identifiable overloads reject null'() {
        given:
        def branch = new BranchDefImpl<Object, Object>('b1')

        when:
        branch."$method"(null)

        then:
        thrown(TransfluxValidationException)

        where:
        method << ['condition', 'step']
    }

    def 'DefaultBranchDef Identifiable overload rejects null'() {
        given:
        def defaultBranch = new DefaultBranchDefImpl<Object, Object>()

        when:
        defaultBranch.step((Identifiable) null)

        then:
        thrown(TransfluxValidationException)
    }
}

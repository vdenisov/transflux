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

package org.transflux.core

import org.transflux.core.action.ActionListenerDef
import org.transflux.core.state.StateListenerDef
import org.transflux.core.transition.TransitionListenerDef
import spock.lang.Specification

import java.lang.reflect.ParameterizedType

/**
 * The listener def surface lives on {@link ListenerDef} alone, so a declaration added there
 * reaches all three categories and none of them drifts.
 */
class ListenerDefSpec extends Specification {

    def '#type.simpleName binds SELF to itself'() {
        when:
        def parent = type.genericInterfaces.find {
            it instanceof ParameterizedType && it.rawType == ListenerDef
        } as ParameterizedType

        then:
        parent != null
        (parent.actualTypeArguments[1] as ParameterizedType).rawType == type

        where:
        type << [StateListenerDef, TransitionListenerDef, ActionListenerDef]
    }

    def '#type.simpleName declares nothing of its own'() {
        expect:
        type.declaredMethods.findAll { !it.synthetic }.isEmpty()

        where:
        type << [StateListenerDef, TransitionListenerDef, ActionListenerDef]
    }
}

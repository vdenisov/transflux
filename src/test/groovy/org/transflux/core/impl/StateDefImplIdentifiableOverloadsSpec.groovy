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

class StateDefImplIdentifiableOverloadsSpec extends Specification {

    private static Identifiable id(String value) {
        return { -> value } as Identifiable
    }

    private StateMachineDefImpl<Object> smd

    void setup() {
        smd = new StateMachineDefImpl<>()
        smd.forEntityType(Object)
        smd.state('s2', {})
    }

    def 'transitionsTo(String target, Identifiable transition, Consumer) registers the transition under the identifiable id'() {
        when:
        smd.state('s1', { s ->
            s.transitionsTo('s2', id('t1'), { t -> })
        })

        then:
        smd.getTransition('t1') != null
    }

    def 'transitionsTo(String target, Identifiable transition, Class<C>, Consumer) registers a typed transition'() {
        when:
        smd.state('s1', { s ->
            s.transitionsTo('s2', id('t1'), String, { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').contextType == String
    }

    def 'transitionsTo(Identifiable target, Identifiable transition, Consumer) registers the transition'() {
        when:
        smd.state('s1', { s ->
            s.transitionsTo(id('s2'), id('t1'), { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').targetStateId == 's2'
    }

    def 'transitionsTo(Identifiable target, Identifiable transition, Class<C>, Consumer) registers a typed transition'() {
        when:
        smd.state('s1', { s ->
            s.transitionsTo(id('s2'), id('t1'), String, { t -> })
        })

        then:
        smd.getTransition('t1') != null
        smd.getTransition('t1').contextType == String
    }

    def 'transitionsTo Identifiable overloads reject null'() {
        given:
        Throwable caught = null

        when:
        try {
            smd.state('s1', { s ->
                action.call(s)
            })
        } catch (Throwable t) {
            caught = t
        }

        then:
        caught instanceof TransfluxValidationException

        where:
        action << [
            { s -> s.transitionsTo('s2', (Identifiable) null, { t -> }) },
            { s -> s.transitionsTo('s2', (Identifiable) null, String, { t -> }) },
            { s -> s.transitionsTo((Identifiable) null, id('t1'), { t -> }) },
            { s -> s.transitionsTo((Identifiable) null, id('t1'), String, { t -> }) },
            { s -> s.transitionsTo(id('s2'), (Identifiable) null, { t -> }) },
            { s -> s.transitionsTo(id('s2'), (Identifiable) null, String, { t -> }) },
        ]
    }
}

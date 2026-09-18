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

package org.transflux.core.logging

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver

import java.util.function.Consumer

/**
 * The entity and the one-transition machine the logging listener specs drive.
 */
class TraceFixture {

    static class Order {
        String id
        String state

        Order(String id, String state) {
            this.id = id
            this.state = state
        }

        @Override
        String toString() {
            return 'ORDER-PAYLOAD'
        }
    }

    static class Payload {
        @Override
        String toString() {
            return 'CONTEXT-PAYLOAD'
        }
    }

    /**
     * Builds {@code s1 -t-> s2}, handing the def to {@code smConfig} and the transition def to
     * {@code body}.
     */
    static StateMachine<Order> machine(Closure smConfig, Closure body = {}) {
        def smd = new StateMachineDefImpl<Order>()
        StateMachineDef<Order> builder = smd.forEntityType(Order)
            .withStateResolver({ o -> o.state } as StateResolver<Order>)
            .withStateApplier({ o, s -> o.state = s } as StateApplier<Order>)
        smConfig.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> body.call(t) } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }
}

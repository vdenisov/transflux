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

package org.transflux.core;

import org.transflux.core.exception.TransfluxValidationException;

/**
 * Main entry point and factory class for the Transflux microflow orchestration library.
 * <p>
 * Transflux provides a lightweight framework for automating the coordination of state changes
 * for business entities. The library focuses on the logic and execution of transitions
 * themselves - handling dependencies, sequencing, error handling, and compensations during
 * state changes - rather than just defining states or managing long-term processes.
 * 
 * <p>This utility class serves as the primary entry point for creating state machine
 * definitions using the framework's fluent API. It provides static factory methods
 * that begin the state machine definition process.
 * 
 * <p><b>Key Features:</b>
 * <ul>
 * <li>Lightweight and non-imposing, easily integrating with existing codebases</li>
 * <li>Embedded and fully local to the application instance</li>
 * <li>Capable of orchestrating complex transitions with error compensation</li>
 * <li>Supportive of reusable components and declarative definitions</li>
 * </ul>
 * 
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // Create a state machine for subscription entities
 * StateMachine<Subscription> subscriptionSM = Transflux
 *     .defineStateMachine()
 *     .forEntityType(Subscription.class)
 *     .withName("Subscription Lifecycle")
 *     .withStateResolver(subscription -> subscription.getStatus())
 *     .state("trial")
 *         .withName("Trial Period")
 *         .transitionsTo("active", "upgrade")
 *         .transitionsTo("expired", "expire")
 *     .state("active")
 *         .withName("Active Subscription")
 *         .transitionsTo("cancelled", "cancel")
 *     .state("expired")
 *         .withName("Expired Subscription")
 *     .state("cancelled")
 *         .withName("Cancelled Subscription")
 *     .build();
 * }</pre>
 * 
 */
public final class Transflux {
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private Transflux() { }

    /**
     * Creates a new state machine definition builder for the specified entity type.
     * <p>
     * This static factory method provides the entry point for defining state machines
     * using Transflux's declarative fluent API. Unlike the previous approach that used
     * a separate {@code forEntityType} method call, this method takes the entity type
     * directly as a parameter, making the API more concise and the entity type specification
     * explicit from the start. The returned {@link StateMachineDef} instance can be used
     * to configure states, transitions, and other state machine properties.
     * 
     * @param <T> the type of entity that will be managed by the state machine
     *
     * @return a new StateMachineDef instance for building the state machine definition
     * @throws TransfluxValidationException if the entity type is null
     */
    public static <T, C> StateMachineDef<T, C> defineStateMachine() {
        return new StateMachineDefImpl<>();
    }
}

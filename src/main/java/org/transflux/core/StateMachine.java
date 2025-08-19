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

/**
 * The central orchestrator that manages entity state transitions and coordinates all framework operations.
 * <p>
 * StateMachine is the core component of the Transflux framework that provides a standardized
 * approach to finite-state machine entities and associated transition workflows. It handles
 * the logic and execution of transitions themselves, including dependencies, sequencing,
 * error handling, and compensations during state changes.
 * 
 * <p><b>Key Responsibilities:</b>
 * <ul>
 * <li>Maintain the state transition matrix definition</li>
 * <li>Validate transition requests against defined rules</li>
 * <li>Execute transition operations and manage their lifecycle</li>
 * <li>Handle trigger evaluation and activation</li>
 * <li>Coordinate pre/post-conditions and listeners</li>
 * </ul>
 * 
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // Create a state machine for subscription entities
 * StateMachine<Subscription> subscriptionSM = Transflux
 *     .defineStateMachine()
 *     .forEntityType(Subscription.class)
 *     .withStateResolver(subscription -> subscription.getStatus())
 *     .state("trial")
 *         .withName("Trial Period")
 *         .transitionsTo("active", "upgrade-transition")
 *         .transitionsTo("expired", "expire-transition")
 *     .state("active")
 *         .withName("Active Subscription")
 *         .transitionsTo("cancelled", "cancel-transition")
 *     .state("expired")
 *         .withName("Expired Subscription")
 *     .state("cancelled")
 *         .withName("Cancelled Subscription")
 *     .build();
 * }</pre>
 * 
 * @param <T> the type of entity managed by this state machine
 */
public interface StateMachine<T> {

}

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
 * Functional interface for resolving the current state of business entities.
 * <p>
 * StateResolver implementations are responsible for determining the current state
 * of an entity by examining its properties or database state. This interface
 * bridges the gap between your domain entities and the Transflux state machine
 * framework, allowing the framework to understand the current state of entities
 * without imposing specific storage or modeling requirements.
 * 
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // For a subscription entity with a status field
 * StateResolver<Subscription> resolver = subscription -> subscription.getStatus();
 * 
 * // For more complex state resolution logic
 * StateResolver<Order> orderResolver = order -> {
 *     if (order.isPaid() && order.isShipped()) {
 *         return "completed";
 *     } else if (order.isPaid()) {
 *         return "processing";
 *     } else {
 *         return "pending";
 *     }
 * };
 * }</pre>
 * 
 * @param <T> the type of entity whose state is being resolved
 */
@FunctionalInterface
public interface StateResolver<T> {
    
    /**
     * Resolves the current state identifier for the given entity.
     * <p>
     * This method should examine the entity's properties, database state, or any
     * other relevant information to determine which state the entity is currently in.
     * The returned state ID must correspond to a state defined in the associated
     * state machine definition.
     * 
     * @param entity the entity whose current state should be resolved, must not be {@code null}
     *
     * @return the current state identifier of the entity, never {@code null} or blank
     *
     * @throws NullPointerException if the entity is {@code null}
     * @throws IllegalStateException if the entity's state cannot be determined or
     *         doesn't correspond to any defined state
     */
    String resolveState(T entity);
}

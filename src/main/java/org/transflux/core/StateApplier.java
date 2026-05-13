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
 * Functional interface for applying the new state to an entity after a successful transition.
 * <p>
 * The state applier is the write-side counterpart to {@link StateResolver}. The framework
 * invokes it exactly once during the execution of a transition, after all post-conditions
 * have passed and immediately before {@code onComplete} listeners are notified. This call
 * is the moment Transflux considers the transition committed.
 *
 * <p>The applier may be implemented as a dedicated class, a lambda, or — via the YAML DSL
 * — a SpEL property path that the framework writes through.
 *
 * <p>Steps and operations may freely mutate the entity in place during the transition;
 * those mutations are part of the host's domain model and are visible to the host
 * immediately. The applier's responsibility is solely to write the new state identifier
 * to whatever field or computed location the host uses for state tracking.
 *
 * <p>The state applier is optional. Some transitions may be purely transient — the host
 * may discard the entity post-transition — in which case the host can omit
 * {@code withStateApplier(...)} and the framework will skip the apply step.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * // Simple field write
 * StateApplier<Subscription> applier = (s, newState) -> s.setStatus(newState);
 *
 * // Class-based applier with injected dependencies
 * public class SubscriptionStateApplier implements StateApplier<Subscription> {
 *     @Inject AuditService audit;
 *
 *     @Override
 *     public void applyState(Subscription subscription, String newStateId) {
 *         subscription.setStatus(newStateId);
 *         audit.recordStateChange(subscription.getId(), newStateId);
 *     }
 * }
 * }</pre>
 *
 * @param <T> the type of entity whose state is being applied
 */
@FunctionalInterface
public interface StateApplier<T> {

    /**
     * Writes the new state identifier to the given entity.
     * <p>
     * Invoked exactly once per successful transition, after post-conditions pass.
     * The {@code newStateId} corresponds to a state defined in the associated state
     * machine.
     *
     * @param entity the entity to update, never {@code null}
     * @param newStateId the new state identifier, never {@code null} or blank
     */
    void applyState(T entity, String newStateId);
}

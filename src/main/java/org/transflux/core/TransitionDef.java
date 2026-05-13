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
 * Definition interface for transitions between states in a state machine.
 * <p>
 * TransitionDef represents the configuration and metadata for a transition,
 * including the unique identifier, source state, target state.
 *
 * <p>TransitionDef instances are created internally by the framework when
 * transitions are registered through the fluent API and should not be
 * instantiated directly by client code.
 *
 * @param <T> the entity type managed by the enclosing state machine
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface TransitionDef<T, C> extends OperationlessTransitionDef<T, C>, Identifiable {

    /**
     * Returns the unique identifier of this transition.
     *
     * @return the transition ID
     */
    @Override
    String getId();

    /**
     * Returns the ID of the source state for this transition.
     *
     * @return the source state ID
     */
    String getSourceStateId();

    /**
     * Returns the ID of the target state for this transition.
     *
     * @return the target state ID
     */
    String getTargetStateId();

    /**
     * Opens a fluent {@link SimpleOperationDef} for this transition. The caller must call
     * {@code .using(...)} on the returned def before the enclosing state machine is built;
     * the def auto-registers itself with this transition.
     *
     * @param id the operation id
     *
     * @return a {@code SimpleOperationDef} bound to this transition
     */
    SimpleOperationDef<T, C> operation(String id);

    /**
     * Convenience: registers a simple operation by id and instance in one call.
     *
     * @param id the operation id
     * @param operation the operation instance
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> operation(String id, Operation<T, C> operation);

    /**
     * Convenience: registers a simple operation by id and class in one call.
     *
     * @param id the operation id
     * @param operationClass the operation class (must have a public no-arg constructor)
     *
     * @return this transition def for chaining
     */
    TransitionDef<T, C> operation(String id, Class<? extends Operation<T, C>> operationClass);

    // TODO: composite operations
}

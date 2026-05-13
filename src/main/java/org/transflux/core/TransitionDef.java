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
 * @param <T> the type of business entity used by the state machine this transition belongs to
 */
public interface TransitionDef<T> extends OperationlessTransitionDef<T>, Identifiable {

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

    // TODO: Select operation by id from library, when component library is implemented
    //OperationlessTransitionDef<T> operation(String id);
    //OperationlessTransitionDef<T> operation(Identifiable operation);

    <C> OperationlessTransitionDef<T> operation(Class<Operation<T, C>> simpleOperationClass);
    <C> OperationlessTransitionDef<T> operation(Operation<T, C> simpleOperation);

    // TODO: composite operations
}
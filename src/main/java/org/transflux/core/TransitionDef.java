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
 * including the unique identifier, source state, and target state. This interface
 * provides access to transition information during state machine operation and
 * introspection.
 * 
 * <p>TransitionDef instances are created internally by the framework when
 * transitions are registered through the fluent API and should not be
 * instantiated directly by client code.
 * 
 */
public interface TransitionDef {

    /**
     * Returns the unique identifier of this transition.
     * 
     * @return the transition ID
     */
    String getId();

    /**
     * Returns the identifier of the source state for this transition.
     * 
     * @return the source state ID
     */
    String getSourceStateId();

    /**
     * Returns the identifier of the target state for this transition.
     * 
     * @return the target state ID
     */
    String getTargetStateId();
}
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
 * Represents a state in the state machine with associated metadata and behavior.
 * <p>
 * States are characterized by their transition patterns rather than explicit types:
 * <ul>
 * <li><b>Initial states</b> have no incoming transitions and serve as entry points where entities begin their lifecycle</li>
 * <li><b>Terminal states</b> have no outgoing transitions and represent final states in the entity lifecycle</li>
 * <li><b>Intermediate states</b> can have both incoming and outgoing transitions</li>
 * </ul>
 * 
 * <p>States contain metadata such as human-readable names and descriptions to support
 * documentation, user interfaces, and logging.
 */
public interface State extends Identifiable {
    
    /**
     * Returns the human-readable name of this state.
     * <p>
     * The name provides a human-readable description or display name that can be
     * used for documentation, user interfaces, and logging. Unlike the ID, names
     * can contain spaces, special characters, and be more descriptive.
     * 
     * @return the human-readable name of the state, may be {@code null}
     */
    String getName();
    
    /**
     * Returns the description of this state.
     * <p>
     * The description provides additional details about the state's purpose,
     * behavior, or business meaning within the entity's lifecycle.
     * 
     * @return the description of the state, may be {@code null}
     */
    String getDescription();
}

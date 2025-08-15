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
 * Functional interface for components that have unique identifiers.
 * <p>
 * All components in Transflux (states, transitions, operations, steps, conditions, triggers)
 * must have unique identifiers for proper referencing and management within the framework.
 * Component IDs are required properties that must be unique within their component type
 * and are used for internal referencing, component lookup, and programmatic access.
 * 
 * <p>It is recommended to use enums implementing this interface for states and other
 * {@code Identifiable} instances, as they provide type safety and better IDE support.
 * 
 * <p><b>Example usage:</b>
 * <pre>{@code
 * public enum OrderState implements Identifiable {
 *     PENDING("pending"),
 *     PROCESSING("processing"),
 *     SHIPPED("shipped"),
 *     DELIVERED("delivered");
 *     
 *     private final String id;
 *     
 *     OrderState(String id) {
 *         this.id = id;
 *     }
 *     
 *     @Override
 *     public String getId() {
 *         return id;
 *     }
 * }
 * }</pre>
 * 
 */
@FunctionalInterface
public interface Identifiable {
    
    /**
     * Returns the unique identifier for this component.
     * <p>
     * The identifier must be unique within the component type (e.g., all state IDs
     * must be unique within a state machine) and should follow naming conventions
     * suitable for programmatic use such as kebab-case or camelCase.
     * 
     * @return the unique identifier, never {@code null} or blank
     */
    String getId();
}

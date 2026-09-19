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
 * How a definition or a runtime view reports its own id.
 * <p>
 * Every id-bearing {@code *Def} and every runtime view ({@code State}, {@code Transition}, {@code Trigger})
 * extends it. Nothing in the API accepts one as an argument: every method that names a component
 * takes the id as a {@code String}, so there is nothing to gain from implementing it in host code.
 * A host that wants its ids in one place keeps them as {@code String} constants.
 */
public interface Identifiable {

    /**
     * Returns this component's id - the one it was declared under.
     *
     * @return the id; never {@code null} or blank
     */
    String getId();
}

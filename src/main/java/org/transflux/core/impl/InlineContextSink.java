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

package org.transflux.core.impl;

/**
 * Receives one inline declaration during the pass that records what a by-id reference to it would
 * be checked against.
 * <p>
 * Three values rather than two, because visibility is half the answer: an id is only checkable
 * from a position that can resolve it, and the scope that declares it is what says which positions
 * those are.
 */
@FunctionalInterface
interface InlineContextSink {

    /**
     * @param id the declared id
     * @param context the context the declaration runs against
     * @param declaringScope the id of the container or conditional whose scope holds it
     */
    void accept(String id, Class<?> context, String declaringScope);
}

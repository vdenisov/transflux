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
 * Internal resolved form of an event trigger's payload filter.
 * <p>
 * Carries all three bindings an event filter may consult — the event payload, the entity, and the
 * host-supplied context. The expression form uses every binding; the predicate and class forms
 * ignore the context. A trigger with no declared filter resolves to an always-true filter.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
@FunctionalInterface
interface EventFilter<T> {

    boolean test(Object eventData, T entity, Object context);
}

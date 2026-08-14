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

package org.transflux.core.trigger;

/**
 * Runtime view of a data trigger — a transition initiated by a host-driven data-change check.
 * <p>
 * A data trigger gates on a condition over the entity (and the host-supplied context). The host
 * requests re-evaluation through {@code entity(e).processDataChange()}; the framework evaluates each
 * eligible data trigger's condition and fires the first whose gate holds. The library does
 * <b>not</b> watch entity fields, hook into ORM change tracking, or evaluate triggers in the
 * background — re-evaluation happens only on an explicit {@code processDataChange} call.
 * <p>
 * Beyond the common {@link Trigger} metadata this subtype adds no further accessors; it exists so
 * the catalog can be filtered by kind through
 * {@link org.transflux.core.StateMachine#getTriggers(Class)}.
 */
public interface DataTrigger extends Trigger {
}

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
 * Runtime view of a manual trigger — a named, host-invoked handle on a transition.
 * <p>
 * A manual trigger is fired explicitly through {@code entity(e).fire(triggerId)}; it carries no
 * automatic activation. Beyond the common {@link Trigger} metadata it adds no further accessors;
 * the subtype exists so the catalog can be filtered by kind through
 * {@link org.transflux.core.StateMachine#getTriggers(Class)}.
 */
public interface ManualTrigger extends Trigger {
}

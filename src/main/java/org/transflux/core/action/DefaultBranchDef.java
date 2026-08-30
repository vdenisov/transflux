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

package org.transflux.core.action;



/**
 * Sub-builder for the default branch of a {@link ConditionalOperationDef}.
 * <p>
 * The default branch carries no condition; the framework runs its steps when every
 * preceding {@link BranchDef} evaluated to {@code false}. The default branch must declare
 * at least one member.
 *
 * <p>Its members are declared through the grammar every ordered action list shares; see
 * {@link ActionSequence}. The default branch adds nothing to it - it has neither a condition nor
 * an identity of its own.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
public interface DefaultBranchDef<T, C> extends ActionSequence<T, C, DefaultBranchDef<T, C>> {
}

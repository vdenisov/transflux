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

import java.util.List;

/**
 * Resolved view of a conditional branch held by the conditional executor — the branch id
 * paired with its resolved {@link BoundCondition} and the ordered list of step ids the
 * branch will dispatch when its condition matches.
 *
 * @param branchId the branch id
 * @param condition the resolved branch condition
 * @param stepIds the ordered step ids the branch dispatches when its condition matches
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record ResolvedBranch<T, C>(String branchId, BoundCondition<T, C> condition, List<String> stepIds) {
}

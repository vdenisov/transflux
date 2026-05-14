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

package org.transflux.core.operation;

/**
 * Behavior selector for a {@link ConditionalStepDef} when no branch condition matches and no
 * default branch is declared.
 * <ul>
 *   <li>{@link #WARN} — log a warning and skip the conditional step. The enclosing transition
 *       continues with the next step.</li>
 *   <li>{@link #SILENT} — skip the conditional step without logging. Suits the guard pattern
 *       where a no-match is a normal, expected outcome (the conditional models
 *       {@code if (cond) { ... }} with no {@code else}).</li>
 *   <li>{@link #ERROR} — raise an error from the conditional step's execution. The enclosing
 *       transition fails and any compensations accumulated so far are drained.</li>
 * </ul>
 * The default is {@link #WARN} — a no-match is more often a misconfigured branch than a
 * deliberate guard, so the library surfaces it by default; deliberate guard patterns opt in
 * to {@code SILENT}.
 */
public enum NoMatchBehavior {
    WARN,
    SILENT,
    ERROR
}

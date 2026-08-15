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
 * The form an {@link Action} was authored in.
 * <p>
 * There is one runtime type for a unit of work; this records which of the two authoring forms
 * produced it, so diagnostics can name the thing the way its author wrote it rather than
 * flattening everything to "action". The kind is metadata: it does not select an execution path,
 * and two actions of different kinds at the same position behave identically.
 */
public enum ActionKind {

    /**
     * An imperative action - a Java body, declared through a {@code step(...)} form. It has no
     * bound children, though it is free to dispatch other actions by id while it runs.
     */
    STEP,

    /**
     * A declarative action - an ordered child list, declared through an {@code operation(...)}
     * or {@code conditional(...)} form. Declaration order is execution order (or, for the
     * conditional variant, the order in which branch conditions are evaluated). There is no
     * Java body; the framework synthesizes the executable that walks the children.
     */
    OPERATION
}

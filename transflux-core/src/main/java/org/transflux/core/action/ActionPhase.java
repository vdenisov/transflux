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
 * Identifies which point of an action's execution an {@link ActionListener} was notified for.
 * <p>
 * A listener attached through a single hook already knows its own phase; the discriminator matters
 * to listeners registered against every action, which observe all three.
 *
 * <p>{@link #COMPLETE} and {@link #ERROR} partition the outcomes: exactly one of them follows every
 * {@link #START}, and neither occurs without one.
 */
public enum ActionPhase {

    /** The action's compensation has been captured and its body is about to run. */
    START,

    /** The action's body returned normally. */
    COMPLETE,

    /**
     * The action's body threw, or an action it dispatched did. The failure is reported at every
     * enclosing level as it propagates outwards, so a container whose member failed is notified
     * too - the whole subtree failed, and each level reports the same throwable.
     */
    ERROR
}

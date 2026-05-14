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

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Package-private discriminated reference to a step inside a composite operation's
 * declaration-time step list.
 * <p>
 * Three kinds:
 * <ul>
 *   <li>{@link ById}        — references a step already registered on the enclosing state
 *       machine; the registry must contain it at build time.</li>
 *   <li>{@link InlineInstance} — declares an id and a {@link Step} instance; auto-registers
 *       on the state machine at build time under the declared id.</li>
 *   <li>{@link InlineClass}    — declares an id and a {@link Step} class; auto-registers,
 *       and the framework reflectively instantiates the class via its public no-arg
 *       constructor at state-machine build time.</li>
 * </ul>
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed interface StepRef<T, C>
    permits StepRef.ById, StepRef.InlineInstance, StepRef.InlineClass {

    String id();

    static <T, C> StepRef<T, C> byId(String id) {
        return new ById<>(id);
    }

    static <T, C> StepRef<T, C> inline(String id, Step<T, C> step) {
        return new InlineInstance<>(id, step);
    }

    static <T, C> StepRef<T, C> inline(String id, Class<? extends Step<T, C>> stepClass) {
        return new InlineClass<>(id, stepClass);
    }

    record ById<T, C>(String id) implements StepRef<T, C> {
        public ById {
            requireNotBlank(id, "Step reference ID");
        }
    }

    record InlineInstance<T, C>(String id, Step<T, C> step) implements StepRef<T, C> {
        public InlineInstance {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(step, "Inline step instance");
        }
    }

    record InlineClass<T, C>(String id, Class<? extends Step<T, C>> stepClass) implements StepRef<T, C> {
        public InlineClass {
            requireNotBlank(id, "Step reference ID");
            requireNotNull(stepClass, "Inline step class");
        }
    }
}

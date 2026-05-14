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

/**
 * Package-private discriminated reference to a step inside a composite operation's
 * declaration-time step list.
 * <p>
 * Three kinds:
 * <ul>
 *   <li>{@code ById}        — references a step already registered on the enclosing state
 *       machine; the registry must contain it at build time.</li>
 *   <li>{@code InlineInstance} — declares an id and a {@link Step} instance; auto-registers
 *       on the state machine at build time under the declared id.</li>
 *   <li>{@code InlineClass}    — declares an id and a {@link Step} class; auto-registers,
 *       and the framework reflectively instantiates the class via its public no-arg
 *       constructor at state-machine build time.</li>
 * </ul>
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
abstract class StepRef<T, C> {

    private final String id;

    private StepRef(String id) {
        if (id == null || id.isBlank()) {
            throw new TransfluxValidationException("Step reference ID cannot be null or blank");
        }
        this.id = id;
    }

    final String getId() {
        return id;
    }

    static <T, C> StepRef<T, C> byId(String id) {
        return new ById<>(id);
    }

    static <T, C> StepRef<T, C> inline(String id, Step<T, C> step) {
        if (step == null) {
            throw new TransfluxValidationException("Inline step instance cannot be null");
        }
        return new InlineInstance<>(id, step);
    }

    static <T, C> StepRef<T, C> inline(String id, Class<? extends Step<T, C>> stepClass) {
        if (stepClass == null) {
            throw new TransfluxValidationException("Inline step class cannot be null");
        }
        return new InlineClass<>(id, stepClass);
    }

    static final class ById<T, C> extends StepRef<T, C> {
        ById(String id) {
            super(id);
        }
    }

    static final class InlineInstance<T, C> extends StepRef<T, C> {
        private final Step<T, C> step;

        InlineInstance(String id, Step<T, C> step) {
            super(id);
            this.step = step;
        }

        Step<T, C> getStep() {
            return step;
        }
    }

    static final class InlineClass<T, C> extends StepRef<T, C> {
        private final Class<? extends Step<T, C>> stepClass;

        InlineClass(String id, Class<? extends Step<T, C>> stepClass) {
            super(id);
            this.stepClass = stepClass;
        }

        Class<? extends Step<T, C>> getStepClass() {
            return stepClass;
        }
    }
}

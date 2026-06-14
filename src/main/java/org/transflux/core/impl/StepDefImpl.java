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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.Step;
import org.transflux.core.operation.StepDef;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link StepDef} implementation.
 * <p>
 * Holds either a {@link Step} instance or a {@code Step} class plus the declared context type;
 * the two source forms are mutually exclusive and last-write-wins.
 * {@link #buildBoundStep()} reflectively instantiates the class form when needed and produces a
 * {@link BoundStep} paired with this def's id.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type this step requires
 */
final class StepDefImpl<T, C> extends IdentifiedDefImpl<StepDefImpl<T, C>> implements StepDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StepDefImpl.class);

    private final Class<C> contextType;
    private final InstanceOrClassSource<Step<T, C>> source;

    StepDefImpl(String id, Class<C> contextType) {
        super(id, "step", "Step ID");
        requireNotNull(contextType, "Step context type");
        this.contextType = contextType;
        this.source = new InstanceOrClassSource<>(log, "Step source", "StepDef '" + id + "'");
    }

    @Override
    public Class<C> contextType() {
        return contextType;
    }

    @Override
    public StepDefImpl<T, C> using(Step<T, C> step) {
        requireConfigurerActive("using");
        requireNotNull(step, "Step");
        source.setInstance(step);
        return this;
    }

    @Override
    public StepDefImpl<T, C> using(Class<? extends Step<T, C>> stepClass) {
        requireConfigurerActive("using");
        requireNotNull(stepClass, "Step class");
        source.setClass(stepClass);
        return this;
    }

    /**
     * Resolves this def into a {@link BoundStep} pairing the step executable with this def's id.
     *
     * @return the bound step
     *
     * @throws TransfluxValidationException if no step source has been set
     */
    BoundStep<T, C> buildBoundStep() {
        return BoundStep.of(getId(), source.resolve("Step"));
    }
}

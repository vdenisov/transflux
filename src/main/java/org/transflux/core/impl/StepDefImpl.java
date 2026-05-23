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

import org.transflux.core.operation.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.transflux.core.exception.TransfluxValidationException;

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.Preconditions.requireNotBlank;
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
final class StepDefImpl<T, C> implements StepDef<T, C> {
    private static final Logger log = LoggerFactory.getLogger(StepDefImpl.class);

    private final String id;
    private final Class<C> contextType;

    private String name;
    private String description;

    private Step<T, C> stepInstance;
    private Class<? extends Step<T, C>> stepClass;

    public StepDefImpl(String id, Class<C> contextType) {
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Step context type");
        this.id = id;
        this.contextType = contextType;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Class<C> contextType() {
        return contextType;
    }

    @Override
    public StepDefImpl<T, C> using(Step<T, C> step) {
        requireNotNull(step, "Step");
        if (this.stepInstance != null || this.stepClass != null) {
            log.warn("Step source already defined for StepDef '{}'; overriding previous value", id);
        }
        this.stepInstance = step;
        this.stepClass = null;
        return this;
    }

    @Override
    public StepDefImpl<T, C> using(Class<? extends Step<T, C>> stepClass) {
        requireNotNull(stepClass, "Step class");
        if (this.stepInstance != null || this.stepClass != null) {
            log.warn("Step source already defined for StepDef '{}'; overriding previous value", id);
        }
        this.stepClass = stepClass;
        this.stepInstance = null;
        return this;
    }

    @Override
    public StepDefImpl<T, C> withName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public StepDefImpl<T, C> withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Resolves this def into a {@link BoundStep} pairing the step executable with this def's id.
     *
     * @return the bound step
     *
     * @throws TransfluxValidationException if no step source has been set
     */
    public BoundStep<T, C> buildBoundStep() {
        if (stepInstance == null && stepClass == null) {
            throw new TransfluxValidationException(
                "StepDef '" + id + "' has no step set; call using(...) before build");
        }

        Step<T, C> resolved = stepInstance != null
            ? stepInstance
            : instantiateNoArg(stepClass, "Step");

        return BoundStep.of(id, resolved);
    }
}

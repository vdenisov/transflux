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

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.trigger.Trigger;

import java.util.List;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Shared base for the runtime trigger family, carrying the catalog metadata that makes up the whole
 * of the public {@link Trigger} contract.
 * <p>
 * Beyond the metadata it declares the two seams the dispatch path needs from every kind: the
 * pre-conditions a trigger contributes on top of its transition's own gate, and whether the trigger
 * may be fired directly by the host. Both default to the behaviour of a gate-less, host-driven kind,
 * so a new kind only overrides what actually differs.
 */
sealed abstract class TriggerImpl implements Trigger
    permits ManualTriggerImpl, EventTriggerImpl, DataTriggerImpl {

    private final String id;
    private final String name;
    private final String description;
    private final String transitionId;

    TriggerImpl(String id, String name, String description, String transitionId) {
        requireNotBlank(id, "Trigger ID");
        requireNotBlank(transitionId, "Trigger transition ID");
        this.id = id;
        this.name = name;
        this.description = description;
        this.transitionId = transitionId;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public final String getTransitionId() {
        return transitionId;
    }

    /**
     * Returns the pre-conditions this trigger contributes on top of its transition's own, evaluated
     * after them. Kinds whose gate lives elsewhere — an event filter, a data-trigger condition —
     * contribute none.
     *
     * @return the trigger-scoped pre-conditions in declaration order; never {@code null}
     */
    List<? extends BoundCondition<?, ?>> preConditions() {
        return List.of();
    }

    /**
     * Verifies that this trigger may be fired directly by the host. Kinds whose gate is only
     * evaluated by a dispatch entry point reject the attempt, because firing them directly would
     * skip that gate entirely.
     *
     * @throws TransfluxValidationException if this trigger is not directly fireable
     */
    void checkDirectlyFireable() {
        throw new TransfluxValidationException(
            "Trigger '" + getId() + "' is " + kindPhrase() + " and cannot be fired directly; use "
                + dispatchEntryPoint() + " instead");
    }

    /**
     * Returns the article-carrying kind phrase used in diagnostics, e.g. {@code "an event trigger"}.
     *
     * @return the kind phrase
     */
    abstract String kindPhrase();

    /**
     * Returns the entry point that fires this kind, e.g. {@code "processEvent(...)"}.
     *
     * @return the entry-point phrase
     */
    abstract String dispatchEntryPoint();
}

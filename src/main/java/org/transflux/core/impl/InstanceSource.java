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
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Encapsulates the "one slot, last-write-wins with a warning, rejected when never set" pattern
 * shared by the {@code using(...)} and {@code withCompensation(...)} overloads across the def
 * hierarchy.
 * <p>
 * Each instance holds one slot and emits an override warning through the owner's logger whenever
 * a subsequent {@link #setInstance} replaces a previously-set value.
 *
 * @param <X> the executable type stored in the source (e.g. {@code Action<T, C>})
 */
final class InstanceSource<X> {

    private final Logger log;
    private final String sourceLabel;
    private final String ownerLabel;

    private X instance;

    InstanceSource(Logger log, String sourceLabel, String ownerLabel) {
        this.log = log;
        this.sourceLabel = sourceLabel;
        this.ownerLabel = ownerLabel;
    }

    void setInstance(X incoming) {
        warnIfSet();
        this.instance = incoming;
    }

    boolean isSet() {
        return instance != null;
    }

    X resolve(String kindLabel) {
        if (instance == null) {
            throw new TransfluxValidationException(
                ownerLabel + " has no " + kindLabel.toLowerCase() + " set; call using(...) before build");
        }
        return instance;
    }

    /**
     * Resolves an optional slot: the held value when one was declared, {@code null} when none
     * was. Owners of a slot that may legitimately stay empty - a def's compensation, say - go
     * through here rather than pairing {@link #isSet()} with {@link #resolve(String)} themselves,
     * so the "declared or not" rule has one definition.
     *
     * @param kindLabel the label naming what is being resolved, used in diagnostics
     *
     * @return the resolved value, or {@code null} when nothing was declared
     */
    X resolveOptional(String kindLabel) {
        return isSet() ? resolve(kindLabel) : null;
    }

    private void warnIfSet() {
        ValidationUtils.warnIfSet(isSet(), sourceLabel, ownerLabel, log);
    }
}

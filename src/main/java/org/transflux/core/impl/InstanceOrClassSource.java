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

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;

/**
 * Encapsulates the "instance XOR class, last-write-wins with warning, resolve via
 * {@link ReflectionUtils#instantiateNoArg}" pattern shared by the {@code using(...)} overloads of
 * {@link StepDefImpl}, {@link SimpleOperationDefImpl}, and {@link MapperDefImpl}.
 * <p>
 * Each instance holds one of two mutually exclusive slots and emits an override warning through
 * the owner's logger whenever a subsequent {@link #setInstance} or {@link #setClass} replaces a
 * previously-set value. {@link #resolve} returns the held instance, or reflectively instantiates
 * the held class through its public no-arg constructor.
 *
 * @param <X> the executable type stored in the source (e.g. {@code Step<T, C>})
 */
final class InstanceOrClassSource<X> {

    private final Logger log;
    private final String sourceLabel;
    private final String ownerLabel;

    private X instance;
    private Class<? extends X> klass;

    InstanceOrClassSource(Logger log, String sourceLabel, String ownerLabel) {
        this.log = log;
        this.sourceLabel = sourceLabel;
        this.ownerLabel = ownerLabel;
    }

    void setInstance(X incoming) {
        warnIfSet();
        this.instance = incoming;
        this.klass = null;
    }

    void setClass(Class<? extends X> incoming) {
        warnIfSet();
        this.klass = incoming;
        this.instance = null;
    }

    boolean isSet() {
        return instance != null || klass != null;
    }

    void clear() {
        this.instance = null;
        this.klass = null;
    }

    X resolve(String kindLabel) {
        if (instance != null) {
            return instance;
        }
        if (klass != null) {
            return instantiateNoArg(klass, kindLabel);
        }
        throw new TransfluxValidationException(
            ownerLabel + " has no " + kindLabel.toLowerCase() + " set; call using(...) before build");
    }

    /**
     * The resolution half of the helper without the mutability or warning: returns
     * {@code instance} when non-null, else instantiates {@code klass} via
     * {@link ReflectionUtils#instantiateNoArg}. Used by call sites that already hold the two
     * slots as immutable fields (e.g. the SM-level registration records in
     * {@link StateMachineDefImpl}).
     */
    static <X> X resolve(X instance, Class<? extends X> klass, String kindLabel) {
        return instance != null ? instance : instantiateNoArg(klass, kindLabel);
    }

    private void warnIfSet() {
        if (isSet()) {
            log.warn("{} already defined for {}; overriding previous value", sourceLabel, ownerLabel);
        }
    }
}

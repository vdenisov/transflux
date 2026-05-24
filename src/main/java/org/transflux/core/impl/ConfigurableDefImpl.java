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

import java.util.function.Consumer;

/**
 * Abstract base for definition impls whose mutating surface is gated by a lambda configurer.
 * <p>
 * The base carries the {@code configurerActive} flag, the begin/end helpers that enclosing
 * parents flip around a configurer invocation, and the {@link #requireConfigurerActive(String)}
 * guard that every mutator calls before applying its effect. Subclasses implement
 * {@link #defLabel()} to embed an identifying phrase ("state 'draft'", "forContext scope for
 * SubmitCtx", "default branch on conditional 'route'") in the guard error message.
 *
 * <p>This is the id-less root; the id-bearing specialization lives on {@link IdentifiedDefImpl}.
 * Defs that have no user-facing id (forContext scopes, default branches, regular branches)
 * extend this class directly. This is framework-internal infrastructure; user code should not
 * invoke it directly.
 */
abstract class ConfigurableDefImpl {

    private boolean configurerActive;

    /**
     * Marks this def as actively under construction by its configurer lambda. Flipped by the
     * enclosing parent around the configurer invocation; user code must not call this directly.
     */
    void beginConfigurer() {
        this.configurerActive = true;
    }

    /**
     * Clears the configurer-active flag once the lambda returns.
     */
    void endConfigurer() {
        this.configurerActive = false;
    }

    /**
     * Returns the human-readable identifying phrase for this def, used inside guard error
     * messages. Example values: {@code "state 'draft'"}, {@code "transition 'submit'"},
     * {@code "forContext scope for SubmitCtx"}, {@code "default branch"}.
     *
     * @return the identifying phrase
     */
    protected abstract String defLabel();

    /**
     * Throws {@link TransfluxValidationException} when called outside the configurer-active
     * window. Every mutating method on a subclass calls this first.
     *
     * @param operation the name of the mutating method, surfaced in the error message
     *
     * @throws TransfluxValidationException if the configurer is not currently active
     */
    protected final void requireConfigurerActive(String operation) {
        if (!configurerActive) {
            throw new TransfluxValidationException(
                "Cannot call '" + operation + "' on " + defLabel()
                    + " after its configurer has returned. "
                    + "The reference is inert; declare children inside the configurer lambda.");
        }
    }

    /**
     * Runs the supplied configurer lambda with {@link #beginConfigurer()} active for the duration
     * of the call, restoring the previous state on normal return or on a thrown exception.
     *
     * @param child the def whose configurer is being invoked; never {@code null}
     * @param configurer the configurer lambda; never {@code null}
     * @param <D> the def type
     */
    static <D extends ConfigurableDefImpl> void runConfigurer(D child, Consumer<? super D> configurer) {
        child.beginConfigurer();
        try {
            configurer.accept(child);
        } finally {
            child.endConfigurer();
        }
    }
}

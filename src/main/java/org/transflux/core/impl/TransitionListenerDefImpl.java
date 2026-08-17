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
import org.transflux.core.transition.TransitionListener;
import org.transflux.core.transition.TransitionListenerDef;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link TransitionListenerDef} implementation.
 * <p>
 * Holds either a {@link TransitionListener} instance or a listener class; the two source forms are
 * mutually exclusive and last-write-wins. {@link #buildBoundListener()} reflectively instantiates
 * the class form when needed and produces a {@link BoundTransitionListener} paired with this def's
 * id and metadata.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class TransitionListenerDefImpl<T, C> extends IdentifiedDefImpl<TransitionListenerDefImpl<T, C>>
        implements TransitionListenerDef<T, C> {

    private final InstanceOrClassSource<TransitionListener<T, C>> source;

    TransitionListenerDefImpl(String id) {
        super(id, "transition listener", "Transition listener ID");
        this.source = new InstanceOrClassSource<>(Loggers.BUILD_VALIDATION, "Transition listener source",
                                                  "TransitionListenerDef '" + id + "'");
    }

    @Override
    public TransitionListenerDefImpl<T, C> using(TransitionListener<T, C> listener) {
        requireConfigurerActive("using");
        requireNotNull(listener, "Transition listener");
        source.setInstance(listener);
        return this;
    }

    @Override
    public TransitionListenerDefImpl<T, C> using(Class<? extends TransitionListener<T, C>> listenerClass) {
        requireConfigurerActive("using");
        requireNotNull(listenerClass, "Transition listener class");
        source.setClass(listenerClass);
        return this;
    }

    /**
     * Resolves this def into a {@link BoundTransitionListener} pairing the listener executable
     * with this def's id, name, and description.
     *
     * @return the bound listener
     *
     * @throws TransfluxValidationException if no listener source has been set
     */
    BoundTransitionListener<T, C> buildBoundListener() {
        if (!source.isSet()) {
            throw new TransfluxValidationException(
                "Transition listener '" + getId()
                    + "' declares no listener; call using(...) in its configurer");
        }
        return new BoundTransitionListener<>(getId(), getName(), getDescription(),
                                             source.resolve("Transition listener"));
    }
}

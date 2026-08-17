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
import org.transflux.core.state.StateListener;
import org.transflux.core.state.StateListenerDef;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link StateListenerDef} implementation.
 * <p>
 * Holds either a {@link StateListener} instance or a listener class; the two source forms are
 * mutually exclusive and last-write-wins. {@link #buildBoundListener()} reflectively instantiates
 * the class form when needed and produces a {@link BoundStateListener} paired with this def's id
 * and metadata.
 *
 * @param <T> the entity type the surrounding state machine manages
 */
final class StateListenerDefImpl<T> extends IdentifiedDefImpl<StateListenerDefImpl<T>>
        implements StateListenerDef<T> {

    private final InstanceOrClassSource<StateListener<T>> source;

    StateListenerDefImpl(String id) {
        super(id, "state listener", "State listener ID");
        this.source = new InstanceOrClassSource<>(Loggers.BUILD_VALIDATION, "State listener source",
                                                  "StateListenerDef '" + id + "'");
    }

    @Override
    public StateListenerDefImpl<T> using(StateListener<T> listener) {
        requireConfigurerActive("using");
        requireNotNull(listener, "State listener");
        source.setInstance(listener);
        return this;
    }

    @Override
    public StateListenerDefImpl<T> using(Class<? extends StateListener<T>> listenerClass) {
        requireConfigurerActive("using");
        requireNotNull(listenerClass, "State listener class");
        source.setClass(listenerClass);
        return this;
    }

    /**
     * Resolves this def into a {@link BoundStateListener} pairing the listener executable with
     * this def's id, name, and description.
     *
     * @return the bound listener
     *
     * @throws TransfluxValidationException if no listener source has been set
     */
    BoundStateListener<T> buildBoundListener() {
        if (!source.isSet()) {
            throw new TransfluxValidationException(
                "State listener '" + getId() + "' declares no listener; call using(...) in its configurer");
        }
        return new BoundStateListener<>(getId(), getName(), getDescription(),
                                        source.resolve("State listener"));
    }
}

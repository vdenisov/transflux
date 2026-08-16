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
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.exception.TransfluxValidationException;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Default {@link ActionListenerDef} implementation.
 * <p>
 * Holds either an {@link ActionListener} instance or a listener class; the two source forms are
 * mutually exclusive and last-write-wins. {@link #buildBoundListener()} reflectively instantiates
 * the class form when needed and produces a {@link BoundActionListener} paired with this def's id
 * and metadata.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the observed action runs against
 */
final class ActionListenerDefImpl<T, C> extends IdentifiedDefImpl<ActionListenerDefImpl<T, C>>
        implements ActionListenerDef<T, C> {

    private static final Logger log = LoggerFactory.getLogger(ActionListenerDefImpl.class);

    private final InstanceOrClassSource<ActionListener<T, C>> source;

    ActionListenerDefImpl(String id) {
        super(id, "action listener", "Action listener ID");
        this.source = new InstanceOrClassSource<>(log, "Action listener source",
                                                  "ActionListenerDef '" + id + "'");
    }

    @Override
    public ActionListenerDefImpl<T, C> using(ActionListener<T, C> listener) {
        requireConfigurerActive("using");
        requireNotNull(listener, "Action listener");
        source.setInstance(listener);
        return this;
    }

    @Override
    public ActionListenerDefImpl<T, C> using(Class<? extends ActionListener<T, C>> listenerClass) {
        requireConfigurerActive("using");
        requireNotNull(listenerClass, "Action listener class");
        source.setClass(listenerClass);
        return this;
    }

    /**
     * Resolves this def into a {@link BoundActionListener} pairing the listener executable with
     * this def's id, name, and description.
     *
     * @return the bound listener
     *
     * @throws TransfluxValidationException if no listener source has been set
     */
    BoundActionListener<T, C> buildBoundListener() {
        if (!source.isSet()) {
            throw new TransfluxValidationException(
                "Action listener '" + getId() + "' declares no listener; call using(...) in its configurer");
        }
        return new BoundActionListener<>(getId(), getName(), getDescription(),
                                         source.resolve("Action listener"));
    }
}

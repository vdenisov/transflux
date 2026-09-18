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

package org.transflux.core.logging;

import org.slf4j.event.Level;
import org.transflux.core.action.ActionListener;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.state.StateListener;
import org.transflux.core.transition.TransitionListener;

import java.util.function.Function;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * How the shipped logging listeners write an execution trace, and the factory for those listeners.
 * <p>
 * Immutable: every {@code with...} returns a new value, so one value can configure the global
 * registration and a differently-configured copy can serve a single owner. Lines go to
 * {@code org.transflux.trace.state}, {@code .transition} and {@code .action}.
 *
 * @param <T> the entity type the listeners observe
 */
public final class ExecutionLogging<T> {

    private final Level level;
    private final boolean context;
    private final boolean timings;
    private final Function<? super T, String> entityLabel;

    private ExecutionLogging(Level level, boolean context, boolean timings,
                             Function<? super T, String> entityLabel) {
        this.level = level;
        this.context = context;
        this.timings = timings;
        this.entityLabel = entityLabel;
    }

    /**
     * Starts a value logging at {@code DEBUG}, with no context, no timings and no entity label.
     *
     * @param <T> the entity type
     *
     * @return the default value
     */
    public static <T> ExecutionLogging<T> defaults() {
        return atLevel(Level.DEBUG);
    }

    /**
     * Starts a value logging every line at {@code level}, with no context, no timings and no entity
     * label.
     *
     * @param level the level every line is written at; never {@code null}
     * @param <T> the entity type
     *
     * @return the new value
     *
     * @throws TransfluxValidationException if {@code level} is {@code null}
     */
    public static <T> ExecutionLogging<T> atLevel(Level level) {
        return new ExecutionLogging<>(requireNotNull(level, "Execution logging level"), false, false,
                                      null);
    }

    /**
     * Appends the context to every line. This is the one place the library logs a host payload, so
     * it is off unless asked for; prefer enabling it on a listener attached to the owner whose
     * context is wanted rather than on the global registration.
     *
     * @return a copy that logs the context
     */
    public ExecutionLogging<T> withContext() {
        return new ExecutionLogging<>(level, true, timings, entityLabel);
    }

    /**
     * Appends the duration to the terminal line of every transition and action.
     *
     * @return a copy that logs durations
     */
    public ExecutionLogging<T> withTimings() {
        return new ExecutionLogging<>(level, context, true, entityLabel);
    }

    /**
     * Appends a label for the entity to every line - an id the host chooses, never the entity
     * itself. A method reference or an explicitly-typed lambda lets the entity type be inferred.
     *
     * @param entityLabel how to name an entity; never {@code null}
     * @param <E> the entity type the label reads
     *
     * @return a copy that labels the entity
     *
     * @throws TransfluxValidationException if {@code entityLabel} is {@code null}
     */
    public <E> ExecutionLogging<E> withEntityLabel(Function<? super E, String> entityLabel) {
        return new ExecutionLogging<>(level, context, timings,
                                      requireNotNull(entityLabel, "Entity label"));
    }

    /**
     * Returns a state listener writing to {@code org.transflux.trace.state}.
     *
     * @param <E> the entity type of the state machine it is attached to
     *
     * @return the listener
     */
    public <E extends T> StateListener<E> stateListener() {
        return new LoggingStateListener<>(this);
    }

    /**
     * Returns a transition listener writing to {@code org.transflux.trace.transition}.
     *
     * @param <E> the entity type of the state machine it is attached to
     * @param <C> the context type of the transition it is attached to
     *
     * @return the listener
     */
    public <E extends T, C> TransitionListener<E, C> transitionListener() {
        return new LoggingTransitionListener<>(this);
    }

    /**
     * Returns an action listener writing to {@code org.transflux.trace.action}.
     *
     * @param <E> the entity type of the state machine it is attached to
     * @param <C> the context type of the action it is attached to
     *
     * @return the listener
     */
    public <E extends T, C> ActionListener<E, C> actionListener() {
        return new LoggingActionListener<>(this);
    }

    Level level() {
        return level;
    }

    boolean logsContext() {
        return context;
    }

    boolean logsTimings() {
        return timings;
    }

    String labelOf(T entity) {
        return entityLabel == null ? null : entityLabel.apply(entity);
    }
}

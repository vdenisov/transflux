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

import org.transflux.core.Identifiable;
import org.transflux.core.action.ActionListenerDef;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.ActionPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Shared implementation and storage for the action-listener hook family that every action def
 * exposes.
 * <p>
 * The family is eighteen overloads wide - three hooks, each in an instance, class, and configurer
 * form, each with an {@link Identifiable} sibling. Every owning def declares one sink and
 * implements its public methods as one-line delegates, so validation order, argument labels, and
 * the configurer guard are written once.
 *
 * <p>A sink lives on {@code StepDefImpl}, {@code OperationDefImpl} and
 * {@code ConditionalOperationDefImpl} rather than on their common base: the conditional's impl
 * deliberately extends {@link IdentifiedDefImpl} instead of the sealed {@code ActionDefImpl}, so
 * there is no single base to hang the storage on.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the owning action runs against
 * @param <D> the def interface returned by every overload, for fluent chaining
 */
final class ActionListenerSink<T, C, D> {

    private final ConfigurableDefImpl owner;
    private final D self;

    private final List<ActionListenerDefImpl<T, C>> onStart = new ArrayList<>();
    private final List<ActionListenerDefImpl<T, C>> onComplete = new ArrayList<>();
    private final List<ActionListenerDefImpl<T, C>> onError = new ArrayList<>();

    /**
     * Creates a sink for one action def.
     *
     * @param owner the def whose configurer guard gates every registration
     * @param self the value returned by every overload
     */
    ActionListenerSink(ConfigurableDefImpl owner, D self) {
        this.owner = owner;
        this.self = self;
    }

    D instanceBased(ActionPhase phase, String listenerId, ActionListener<T, C> listener) {
        owner.requireConfigurerActive(hook(phase));
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listener, "Action listener");
        return store(phase, listenerId, l -> l.using(listener));
    }

    D instanceBased(ActionPhase phase, Identifiable listenerIdentifiable, ActionListener<T, C> listener) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return instanceBased(phase, listenerIdentifiable.getId(), listener);
    }

    D classBased(ActionPhase phase, String listenerId,
                 Class<? extends ActionListener<T, C>> listenerClass) {
        owner.requireConfigurerActive(hook(phase));
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(listenerClass, "Action listener class");
        return store(phase, listenerId, l -> l.using(listenerClass));
    }

    D classBased(ActionPhase phase, Identifiable listenerIdentifiable,
                 Class<? extends ActionListener<T, C>> listenerClass) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return classBased(phase, listenerIdentifiable.getId(), listenerClass);
    }

    D configured(ActionPhase phase, String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        owner.requireConfigurerActive(hook(phase));
        requireNotBlank(listenerId, "Action listener ID");
        requireNotNull(configurer, "Action listener configurer");
        return store(phase, listenerId, configurer);
    }

    D configured(ActionPhase phase, Identifiable listenerIdentifiable,
                 Consumer<ActionListenerDef<T, C>> configurer) {
        requireNotNull(listenerIdentifiable, "Action listener identifiable");
        return configured(phase, listenerIdentifiable.getId(), configurer);
    }

    /**
     * Returns the defs collected for one hook, in declaration order.
     *
     * @param phase the hook to read
     *
     * @return an unmodifiable view of that hook's listener defs
     */
    List<ActionListenerDefImpl<T, C>> forPhase(ActionPhase phase) {
        return Collections.unmodifiableList(listFor(phase));
    }

    /**
     * Resolves every collected def into its bound form.
     *
     * @return the three hook lists, in declaration order
     */
    BoundActionListeners<T, C> buildBound() {
        return new BoundActionListeners<>(bind(onStart), bind(onComplete), bind(onError));
    }

    private static <T, C> List<BoundActionListener<T, C>> bind(List<ActionListenerDefImpl<T, C>> defs) {
        if (defs.isEmpty()) {
            return List.of();
        }

        List<BoundActionListener<T, C>> bound = new ArrayList<>(defs.size());
        for (ActionListenerDefImpl<T, C> ld : defs) {
            bound.add(ld.buildBoundListener());
        }

        return bound;
    }

    private D store(ActionPhase phase, String listenerId, Consumer<ActionListenerDef<T, C>> configurer) {
        ActionListenerDefImpl<T, C> listenerDef = new ActionListenerDefImpl<>(listenerId);
        ConfigurableDefImpl.runConfigurer(listenerDef, configurer);
        listFor(phase).add(listenerDef);
        return self;
    }

    private List<ActionListenerDefImpl<T, C>> listFor(ActionPhase phase) {
        return switch (phase) {
            case START -> onStart;
            case COMPLETE -> onComplete;
            case ERROR -> onError;
        };
    }

    /**
     * Returns the DSL method name for a hook, used by the configurer guard and by build-time
     * diagnostics that have to name where a duplicate listener id was declared.
     *
     * @param phase the hook
     *
     * @return the DSL method name
     */
    static String hook(ActionPhase phase) {
        return switch (phase) {
            case START -> "onStart";
            case COMPLETE -> "onComplete";
            case ERROR -> "onError";
        };
    }
}

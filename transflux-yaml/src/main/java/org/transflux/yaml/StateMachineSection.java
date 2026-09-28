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

package org.transflux.yaml;

import org.transflux.core.StateMachineDef;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateDef;
import org.transflux.core.state.StateResolver;
import org.transflux.core.transition.TransitionDef;
import org.transflux.yaml.ConditionDescriptors.Target;
import org.transflux.yaml.DeclarationSites.Namespace;
import org.transflux.yaml.ListenerEntries.Category;
import org.transflux.yaml.ListenerEntries.Hook;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;

import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code stateMachine:} section of the root document: the machine's own metadata, its state
 * accessors, its state-machine-wide listeners, and its states and transitions.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class StateMachineSection {

    private final Classes classes;
    private final Class<?> entityType;
    private final StateMachineDef raw;
    private final ConditionDescriptors conditions;
    private final ListenerEntries listeners;
    private final TriggerEntries triggers;
    private final ActionEntries actions;
    private final DeclarationSites sites;

    private StateMachineSection(Classes classes, Class<?> entityType, StateMachineDef<?> def, DeclarationSites sites) {
        this.classes = classes;
        this.entityType = entityType;
        this.raw = def;
        this.sites = sites;
        this.conditions = new ConditionDescriptors(classes, entityType);
        this.listeners = new ListenerEntries(classes, entityType, sites);
        this.triggers = new TriggerEntries(classes, entityType, conditions, sites);
        this.actions = new ActionEntries(classes, entityType, conditions,
            new ActionKeys(classes, entityType, listeners), sites);
    }

    /**
     * Reads the state machine a root document declares onto the definition.
     *
     * @param section the {@code stateMachine:} mapping, with {@code entityType} already read
     * @param def the definition being built
     * @param entityType the definition's entity type, which each class is checked against
     * @param classes how the document's class names become classes and instances
     * @param sites where this load's ids were first declared
     *
     * @throws DefinitionLoadException when an entry is not valid, or the definition refuses it
     */
    static void read(NodeMap section, StateMachineDef<?> def, Class<?> entityType, Classes classes,
                     DeclarationSites sites) {
        new StateMachineSection(classes, entityType, def, sites).read(section);
    }

    private void read(NodeMap section) {
        metadata(section);
        accessor(section, "stateResolver");
        accessor(section, "stateApplier");
        listeners.hooks(section, null, true, globalHooks());

        int states = each(section, "states", "a state", this::state);
        int transitions = each(section, "transitions", "a transition", this::transition);
        section.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("State machine read, states={}, transitions={}", states, transitions);
    }

    private void metadata(NodeMap section) {
        ComponentSections.metadata(section, raw::withName, raw::withDescription);
        apply(section, "id", raw::withId);
        apply(section, "version", raw::withVersion);
    }

    private void apply(NodeMap section, String key, Consumer<String> setter) {
        String value = section.optionalString(key);
        if (value != null) {
            section.at(section.requiredNode(key), () -> {
                setter.accept(value);
                return null;
            });
        }
    }

    /**
     * Reads {@code stateResolver:} or {@code stateApplier:}: a class, or the expression a Java host
     * writes as a lambda.
     */
    private void accessor(NodeMap section, String key) {
        NodeMap block = section.optionalMap(key);
        if (block == null) {
            return;
        }
        boolean resolver = key.equals("stateResolver");
        String form = block.exactlyOneOf("class", "expression");
        Object accessor;
        if (form.equals("class")) {
            accessor = resolver
                ? classes.instantiate(block, "class", StateResolver.class, Expected.superOf(entityType))
                : classes.instantiate(block, "class", StateApplier.class, Expected.superOf(entityType));
        } else {
            accessor = resolver ? Expressions.resolver(block) : Expressions.applier(block);
        }
        block.rejectUnknownKeys();

        section.at(section.requiredNode(key), () -> resolver
            ? raw.withStateResolver((StateResolver) accessor)
            : raw.withStateApplier((StateApplier) accessor));
        Loggers.YAML_BINDING.debug("State accessor set, key={}, form={}", key, form);
    }

    private List<Hook> globalHooks() {
        return List.of(
            new Hook("onAnyStateEntry", Category.STATE, raw::onAnyStateEntry,
                     (id, cfg) -> raw.onAnyStateEntry(id, cfg)),
            new Hook("onAnyStateExit", Category.STATE, raw::onAnyStateExit,
                     (id, cfg) -> raw.onAnyStateExit(id, cfg)),
            new Hook("onAnyTransitionStart", Category.TRANSITION, raw::onAnyTransitionStart,
                     (id, cfg) -> raw.onAnyTransitionStart(id, cfg)),
            new Hook("onAnyTransitionComplete", Category.TRANSITION, raw::onAnyTransitionComplete,
                     (id, cfg) -> raw.onAnyTransitionComplete(id, cfg)),
            new Hook("onAnyTransitionError", Category.TRANSITION, raw::onAnyTransitionError,
                     (id, cfg) -> raw.onAnyTransitionError(id, cfg)),
            new Hook("onAnyActionStart", Category.ACTION, raw::onAnyActionStart,
                     (id, cfg) -> raw.onAnyActionStart(id, cfg)),
            new Hook("onAnyActionComplete", Category.ACTION, raw::onAnyActionComplete,
                     (id, cfg) -> raw.onAnyActionComplete(id, cfg)),
            new Hook("onAnyActionError", Category.ACTION, raw::onAnyActionError,
                     (id, cfg) -> raw.onAnyActionError(id, cfg)));
    }

    private int each(NodeMap section, String key, String what, Consumer<NodeMap> entry) {
        List<Node> entries = section.optionalList(key);
        if (entries == null) {
            return 0;
        }
        entries.forEach(node -> entry.accept(NodeMap.of(section.document(), node, section.declarationPath(), what)));
        return entries.size();
    }

    private void state(NodeMap entry) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("state '" + id + "'");
        Consumer<StateDef> configurer = state -> {
            ComponentSections.metadata(within, state::withName, state::withDescription);
            // A state listener is handed whichever context the transition carries, so it has none of its own.
            listeners.hooks(within, null, true, List.of(
                new Hook("onEntry", Category.STATE, state::onEntry, (lid, cfg) -> state.onEntry(lid, cfg)),
                new Hook("onExit", Category.STATE, state::onExit, (lid, cfg) -> state.onExit(lid, cfg))));
            ListenerEntries.disables(within, state::disableAllGlobalListeners, state::disableGlobalListeners);
        };
        sites.declare(within, within.requiredNode("id"), Namespace.STATE, id, () -> raw.state(id, configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("State declared, id={}", id);
    }

    private void transition(NodeMap entry) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("transition '" + id + "'");
        String from = within.requiredString("from");
        String to = within.requiredString("to");
        Class<?> declared = classes.optionalClass(within, "context", null);
        // What a transition declares runs against its context, which is Object when it declared none.
        Class<?> context = declared == null ? Object.class : declared;

        Consumer<TransitionDef> configurer = transition -> {
            ComponentSections.metadata(within, transition::withName, transition::withDescription);
            actions.list(within, transition, context);
            conditions.list(within, "preConditions", context, Target.of(
                transition::preCondition, transition::preConditionExpression, transition::preCondition,
                transition::preCondition, transition::preCondition, transition::preCondition));
            conditions.list(within, "postConditions", context, Target.of(
                transition::postCondition, transition::postConditionExpression, transition::postCondition,
                transition::postCondition, transition::postCondition, transition::postCondition));
            List<Node> attached = within.optionalList("triggers");
            if (attached != null) {
                attached.forEach(node -> triggers.attach(within, node, transition, context));
            }
            listeners.hooks(within, declared, false, List.of(
                new Hook("onStart", Category.TRANSITION, transition::onStart,
                         (lid, cfg) -> transition.onStart(lid, cfg)),
                new Hook("onComplete", Category.TRANSITION, transition::onComplete,
                         (lid, cfg) -> transition.onComplete(lid, cfg)),
                new Hook("onError", Category.TRANSITION, transition::onError,
                         (lid, cfg) -> transition.onError(lid, cfg))));
            ListenerEntries.disables(within, transition::disableAllGlobalListeners,
                                     transition::disableGlobalListeners);
        };
        sites.declare(within, within.requiredNode("id"), Namespace.TRANSITION, id, () -> declared == null
            ? raw.transition(id, from, to, configurer)
            : raw.transition(id, from, to, declared, configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Transition declared, id={}, context={}", id, context.getName());
    }
}

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
import org.transflux.core.transition.TransitionDef;
import org.transflux.core.trigger.DataTriggerDef;
import org.transflux.core.trigger.EventTriggerDef;
import org.transflux.core.trigger.ManualTriggerDef;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Triggers as a document writes them: an entry in the {@code triggers:} pool, and an entry in a
 * transition's {@code triggers:} list, which references one or declares one in place.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class TriggerEntries {

    private final Classes classes;
    private final Class<?> entityType;
    private final ConditionDescriptors conditions;
    private final DeclarationSites sites;

    TriggerEntries(Classes classes, Class<?> entityType, ConditionDescriptors conditions, DeclarationSites sites) {
        this.classes = classes;
        this.entityType = entityType;
        this.conditions = conditions;
        this.sites = sites;
    }

    /**
     * Registers one entry of the {@code triggers:} pool.
     *
     * @param entry the entry
     * @param def the definition it is registered on
     *
     * @throws DefinitionLoadException when the entry is not a trigger registration
     */
    void register(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("trigger '" + id + "'");
        String type = within.requiredString("type");
        Class<?> context = classes.optionalClass(within, "context", null);
        Class<?> contextType = context == null ? Object.class : context;
        Consumer configurer = configurer(within, type, contextType);

        sites.declare(within, within.requiredNode("id"), DeclarationSites.Namespace.TRIGGER, id, () -> switch (type) {
            case "manual" -> def.manualTrigger(id, contextType, configurer);
            case "event" -> def.eventTrigger(id, contextType, configurer);
            case "data" -> def.dataTrigger(id, contextType, configurer);
            default -> throw unknownType(type);
        });
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Trigger registered, id={}, type={}", id, type);
    }

    /**
     * Reads one entry of a transition's {@code triggers:} list: a string attaches a registered
     * trigger, a block declares one in place against the transition's context.
     *
     * @param owner the transition's mapping
     * @param entry the entry
     * @param def the transition's def, inside its configurer
     * @param context the transition's context
     *
     * @throws DefinitionLoadException when the entry is neither
     */
    void attach(NodeMap owner, Node entry, TransitionDef<?, ?> def, Class<?> context) {
        if (entry instanceof ScalarNode reference) {
            owner.at(entry, () -> def.addTrigger(reference.getValue()));
            return;
        }
        NodeMap declaration = NodeMap.of(owner.document(), entry, owner.declarationPath(), "a trigger");
        String id = declaration.requiredString("id");
        NodeMap within = declaration.within("trigger '" + id + "'");
        String type = within.requiredString("type");
        Consumer configurer = configurer(within, type, context);

        within.at(within.requiredNode("id"), () -> switch (type) {
            case "manual" -> def.addManualTrigger(id, configurer);
            case "event" -> def.addEventTrigger(id, configurer);
            case "data" -> def.addDataTrigger(id, configurer);
            default -> throw unknownType(type);
        });
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Trigger declared, id={}, type={}", id, type);
    }

    private Consumer<?> configurer(NodeMap within, String type, Class<?> context) {
        return switch (type) {
            case "manual" -> (Consumer<ManualTriggerDef>) trigger -> {
                ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                conditions.list(within, "preConditions", context, ConditionDescriptors.Target.of(
                    trigger::preCondition, trigger::preConditionExpression, trigger::preCondition,
                    trigger::preCondition, trigger::preCondition, trigger::preCondition));
            };
            case "event" -> (Consumer<EventTriggerDef>) trigger -> {
                ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                String event = within.requiredString("event");
                within.at(within.requiredNode("event"), () -> trigger.onEvent(event));
                filter(within, trigger);
            };
            case "data" -> (Consumer<DataTriggerDef>) trigger -> {
                ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                conditions.descriptor(within, within.requiredNode("condition"), context, ConditionDescriptors.Target.of(
                    trigger::condition, trigger::conditionExpression, trigger::condition,
                    trigger::condition, trigger::condition, trigger::condition));
            };
            default -> throw within.error(within.requiredNode("type"),
                "'type' must be one of manual, event, data, not '" + type + "'");
        };
    }

    /**
     * @param type the type the document named
     *
     * @return the failure for a type {@link #configurer} did not already refuse, which is the
     *         kind's dispatch and its configurer disagreeing rather than anything a document did
     */
    private static IllegalStateException unknownType(String type) {
        return new IllegalStateException("Trigger type '" + type + "' has a configurer but no declaration");
    }

    private void filter(NodeMap trigger, EventTriggerDef def) {
        NodeMap filter = trigger.optionalMap("filter", "filter");
        if (filter == null) {
            return;
        }
        Node at = trigger.requiredNode("filter");
        if (filter.exactlyOneOf("class", "expression").equals("class")) {
            Object predicate = conditions.predicate(filter, "class",
                new Expected[] {Expected.exactly(Object.class), Expected.superOf(entityType)},
                Expected.exactly(Object.class));
            filter.at(at, () -> predicate instanceof BiPredicate bi
                ? def.filter(bi)
                : def.filter((Predicate) predicate));
        } else {
            String expression = filter.requiredString("expression");
            filter.at(filter.requiredNode("expression"), () -> def.filterExpression(expression));
        }
        filter.rejectUnknownKeys();
    }
}

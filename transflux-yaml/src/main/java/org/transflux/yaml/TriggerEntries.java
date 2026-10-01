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

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Triggers as a document writes them: an entry in the {@code triggers:} pool, and an entry in a
 * transition's {@code triggers:} list, which references one or declares one in place.
 */
final class TriggerEntries {

    /**
     * One trigger kind's two declarations, each taking the configurer the entry's keys became.
     *
     * @param register registers the trigger on the state machine, under an id
     * @param declare declares the trigger in place on a transition, under an id
     */
    private record Kind(BiFunction<StateMachineDef<Object>, String, ?> register,
                        BiFunction<TransitionDef<Object, Object>, String, ?> declare) {
    }

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
        String id = entry.requiredId("trigger");
        NodeMap within = entry.within("trigger '" + id + "'");
        String type = within.requiredString("type");
        Class<?> context = classes.optionalClass(within, "context", null);
        Kind kind = kind(within, type, context == null ? Object.class : context);

        sites.declare(within, within.requiredNode("id"), DeclarationSites.Namespace.TRIGGER, id,
            () -> kind.register().apply(TypeArguments.overObjects(def), id));
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
        String id = declaration.requiredId("trigger");
        NodeMap within = declaration.within("trigger '" + id + "'");
        String type = within.requiredString("type");
        Kind kind = kind(within, type, context);

        within.at(within.requiredNode("id"), () -> kind.declare().apply(TypeArguments.overObjects(def), id));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Trigger declared, id={}, type={}", id, type);
    }

    /**
     * Resolves a trigger entry's {@code type} into its kind, whose configurer reads the entry's keys.
     *
     * @param within the trigger's mapping
     * @param type the type the entry names
     * @param context the trigger's context, which its conditions are typed against
     *
     * @return the kind
     *
     * @throws DefinitionLoadException when the type is not a trigger kind
     */
    private Kind kind(NodeMap within, String type, Class<?> context) {
        Class<Object> typed = TypeArguments.overObjects(context);
        switch (type) {
            case "manual" -> {
                Consumer<ManualTriggerDef<Object, Object>> configurer = trigger -> {
                    ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                    conditions.list(within, "preConditions", context, ConditionDescriptors.Target.of(
                        trigger::preCondition, trigger::preConditionExpression, trigger::preCondition,
                        trigger::preCondition, trigger::preCondition, trigger::preCondition));
                };
                return new Kind((def, id) -> def.manualTrigger(id, typed, configurer),
                    (def, id) -> def.addManualTrigger(id, configurer));
            }
            case "event" -> {
                Consumer<EventTriggerDef<Object, Object>> configurer = trigger -> {
                    ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                    String event = within.requiredString("event");
                    within.at(within.requiredNode("event"), () -> trigger.onEvent(event));
                    filter(within, trigger);
                };
                return new Kind((def, id) -> def.eventTrigger(id, typed, configurer),
                    (def, id) -> def.addEventTrigger(id, configurer));
            }
            case "data" -> {
                Consumer<DataTriggerDef<Object, Object>> configurer = trigger -> {
                    ComponentSections.metadata(within, trigger::withName, trigger::withDescription);
                    conditions.descriptor(within, within.requiredNode("condition"), context,
                        ConditionDescriptors.Target.of(trigger::condition, trigger::conditionExpression,
                            trigger::condition, trigger::condition, trigger::condition, trigger::condition));
                };
                return new Kind((def, id) -> def.dataTrigger(id, typed, configurer),
                    (def, id) -> def.addDataTrigger(id, configurer));
            }
            default -> throw within.error(within.requiredNode("type"),
                "'type' must be one of manual, event, data, not '" + type + "'");
        }
    }

    private void filter(NodeMap trigger, EventTriggerDef<Object, Object> def) {
        NodeMap filter = trigger.optionalMap("filter", "filter");
        if (filter == null) {
            return;
        }
        Node at = trigger.requiredNode("filter");
        if (filter.exactlyOneOf("class", "expression").equals("class")) {
            Object predicate = conditions.predicate(filter, "class",
                new Expected[] {Expected.exactly(Object.class), Expected.superOf(entityType)},
                Expected.exactly(Object.class));
            filter.at(at, () -> predicate instanceof BiPredicate<?, ?> bi
                ? def.filter(TypeArguments.<BiPredicate<Object, Object>>overObjects(bi))
                : def.filter(TypeArguments.<Predicate<Object>>overObjects(predicate)));
        } else {
            String expression = filter.requiredString("expression");
            filter.at(filter.requiredNode("expression"), () -> def.filterExpression(expression));
        }
        filter.rejectUnknownKeys();
    }
}

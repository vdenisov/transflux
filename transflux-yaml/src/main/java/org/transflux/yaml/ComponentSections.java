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
import org.transflux.core.action.Action;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.MapperDef;
import org.transflux.core.action.StepDef;
import org.transflux.core.condition.Condition;
import org.transflux.core.trigger.DataTriggerDef;
import org.transflux.core.trigger.EventTriggerDef;
import org.transflux.core.trigger.ManualTriggerDef;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The component sections of one document, each entry registered on the definition the document
 * contributes to: {@code steps}, {@code conditions}, {@code mappers}, {@code triggers} and
 * {@code listeners}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class ComponentSections {

    private final Classes classes;
    private final Class<?> entityType;
    private final StateMachineDef raw;
    private final ConditionDescriptors conditions;
    private final ListenerEntries listeners;
    private final ActionKeys actionKeys;

    private ComponentSections(Classes classes, Class<?> entityType, StateMachineDef<?> def) {
        this.classes = classes;
        this.entityType = entityType;
        this.raw = def;
        this.conditions = new ConditionDescriptors(classes, entityType);
        this.listeners = new ListenerEntries(classes, entityType);
        this.actionKeys = new ActionKeys(classes, entityType, listeners);
    }

    /**
     * Registers every component a document declares.
     *
     * @param document the document's root mapping
     * @param def the definition the components are registered on
     * @param entityType the definition's entity type, which each class is checked against
     * @param classes how the document's class names become classes and instances
     *
     * @throws DefinitionLoadException when an entry is not valid, or the definition refuses it
     */
    static void read(NodeMap document, StateMachineDef<?> def, Class<?> entityType, Classes classes) {
        ComponentSections sections = new ComponentSections(classes, entityType, def);
        int count = sections.section(document, "steps", "a step", sections::step)
            + sections.section(document, "conditions", "a condition", sections::condition)
            + sections.section(document, "mappers", "a mapper", sections::mapper)
            + sections.section(document, "triggers", "a trigger", sections::trigger)
            + sections.section(document, "listeners", "a listener", sections.listeners::register);
        Loggers.YAML_BINDING.debug("Component sections read, identifier={}, components={}",
            document.document().identifier(), count);
    }

    private int section(NodeMap document, String key, String what, BiConsumer<NodeMap, StateMachineDef<?>> entry) {
        List<Node> entries = document.optionalList(key);
        if (entries == null) {
            return 0;
        }
        entries.forEach(node -> entry.accept(NodeMap.of(document.document(), node, null, what), raw));
        return entries.size();
    }

    private void step(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("step '" + id + "'");
        Class<?> context = classes.optionalClass(within, "context", null);
        Consumer<StepDef> configurer = step -> {
            Action action = classes.instantiate(within, "class", Action.class,
                Expected.superOf(entityType), exactly(context));
            within.at(within.requiredNode("class"), () -> step.using(action));
            actionKeys.apply(within, step, context);
        };
        within.at(within.requiredNode("id"), () -> context == null
            ? raw.step(id, (Consumer) configurer)
            : raw.step(id, context, (Consumer) configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Step registered, id={}, context={}", id, name(context));
    }

    private void condition(NodeMap entry, StateMachineDef<?> def) {
        Class<?> context = classes.optionalClass(entry, "context", null);
        conditions.declaration(entry, context, true, new ConditionDescriptors.Target() {
            @Override
            public void reference(String id) {
                throw new IllegalStateException("A registration is never a reference");
            }

            @Override
            public void expression(String id, String expression) {
                register(id, expression);
            }

            @Override
            public void condition(String id, Condition<?, ?> condition) {
                register(id, condition);
            }

            @Override
            public void predicate(String id, BiPredicate<?, ?> predicate) {
                register(id, predicate);
            }

            @Override
            public void predicate(String id, Predicate<?> predicate) {
                register(id, predicate);
            }

            // One call per form, so each reaches the overload its static type selects.
            private void register(String id, String expression) {
                if (context == null) {
                    raw.condition(id, expression);
                } else {
                    raw.condition(id, context, expression);
                }
            }

            private void register(String id, Condition condition) {
                if (context == null) {
                    raw.condition(id, condition);
                } else {
                    raw.condition(id, context, condition);
                }
            }

            private void register(String id, BiPredicate predicate) {
                if (context == null) {
                    raw.condition(id, predicate);
                } else {
                    raw.condition(id, context, predicate);
                }
            }

            private void register(String id, Predicate predicate) {
                if (context == null) {
                    raw.condition(id, predicate);
                } else {
                    raw.condition(id, context, predicate);
                }
            }
        });
    }

    private void mapper(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("mapper '" + id + "'");
        Class<?> parentType = classes.requiredClass(within, "parentType", null);
        Class<?> childType = classes.requiredClass(within, "childType", null);
        ContextMapper mapper;
        if (within.exactlyOneOf("class", "mapTo").equals("class")) {
            if (within.optionalNode("mapFrom") != null) {
                throw within.error(within.keyNode("mapFrom"),
                    "'mapFrom' needs 'mapTo' beside it; a class maps back itself");
            }
            mapper = classes.instantiate(within, "class", ContextMapper.class,
                Expected.exactly(parentType), Expected.exactly(childType));
        } else {
            mapper = Expressions.mapper(within);
        }
        Consumer<MapperDef> configurer = mapperDef -> {
            metadata(within, mapperDef::withName, mapperDef::withDescription);
            mapperDef.using(mapper);
        };
        within.at(within.requiredNode("id"), () -> raw.mapperDef(id, parentType, childType, (Consumer) configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Mapper registered, id={}, parentType={}", id, parentType.getName());
    }

    private void trigger(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredString("id");
        NodeMap within = entry.within("trigger '" + id + "'");
        String type = within.requiredString("type");
        Class<?> context = classes.optionalClass(within, "context", null);
        Class<?> contextType = context == null ? Object.class : context;
        Node at = within.requiredNode("id");

        switch (type) {
            case "manual" -> {
                Consumer<ManualTriggerDef> configurer = trigger -> {
                    metadata(within, trigger::withName, trigger::withDescription);
                    conditions.list(within, "preConditions", contextType, manualTarget(trigger));
                };
                within.at(at, () -> raw.manualTrigger(id, contextType, (Consumer) configurer));
            }
            case "event" -> {
                Consumer<EventTriggerDef> configurer = trigger -> {
                    metadata(within, trigger::withName, trigger::withDescription);
                    String event = within.requiredString("event");
                    within.at(within.requiredNode("event"), () -> trigger.onEvent(event));
                    filter(within, trigger);
                };
                within.at(at, () -> raw.eventTrigger(id, contextType, (Consumer) configurer));
            }
            case "data" -> {
                Consumer<DataTriggerDef> configurer = trigger -> {
                    metadata(within, trigger::withName, trigger::withDescription);
                    conditions.descriptor(within, within.requiredNode("condition"), contextType, dataTarget(trigger));
                };
                within.at(at, () -> raw.dataTrigger(id, contextType, (Consumer) configurer));
            }
            default -> throw within.error(within.requiredNode("type"),
                "'type' must be one of manual, event, data, not '" + type + "'");
        }
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Trigger registered, id={}, type={}", id, type);
    }

    private void filter(NodeMap trigger, EventTriggerDef def) {
        NodeMap filter = trigger.optionalMap("filter");
        if (filter == null) {
            return;
        }
        Node at = trigger.requiredNode("filter");
        if (filter.exactlyOneOf("class", "expression").equals("class")) {
            Object predicate = conditions.predicate(filter, "class",
                new Expected[] {Expected.exactly(Object.class), Expected.superOf(entityType)},
                Expected.exactly(Object.class));
            trigger.at(at, () -> predicate instanceof BiPredicate bi
                ? def.filter(bi)
                : def.filter((Predicate) predicate));
        } else {
            String expression = filter.requiredString("expression");
            filter.at(filter.requiredNode("expression"), () -> def.filterExpression(expression));
        }
        filter.rejectUnknownKeys();
    }

    private static ConditionDescriptors.Target manualTarget(ManualTriggerDef def) {
        return new ConditionDescriptors.Target() {
            @Override
            public void reference(String id) {
                def.preCondition(id);
            }

            @Override
            public void expression(String id, String expression) {
                if (id == null) {
                    def.preConditionExpression(expression);
                } else {
                    def.preCondition(id, expression);
                }
            }

            @Override
            public void condition(String id, Condition<?, ?> condition) {
                def.preCondition(id, (Condition) condition);
            }

            @Override
            public void predicate(String id, BiPredicate<?, ?> predicate) {
                def.preCondition(id, (BiPredicate) predicate);
            }

            @Override
            public void predicate(String id, Predicate<?> predicate) {
                def.preCondition(id, (Predicate) predicate);
            }
        };
    }

    private static ConditionDescriptors.Target dataTarget(DataTriggerDef def) {
        return new ConditionDescriptors.Target() {
            @Override
            public void reference(String id) {
                def.condition(id);
            }

            @Override
            public void expression(String id, String expression) {
                if (id == null) {
                    def.conditionExpression(expression);
                } else {
                    def.condition(id, expression);
                }
            }

            @Override
            public void condition(String id, Condition<?, ?> condition) {
                def.condition(id, (Condition) condition);
            }

            @Override
            public void predicate(String id, BiPredicate<?, ?> predicate) {
                def.condition(id, (BiPredicate) predicate);
            }

            @Override
            public void predicate(String id, Predicate<?> predicate) {
                def.condition(id, (Predicate) predicate);
            }
        };
    }

    /**
     * Applies {@code name} and {@code description}, where present.
     *
     * @param map the declaration
     * @param name the def's name setter
     * @param description the def's description setter
     *
     * @throws DefinitionLoadException when either is not a string
     */
    static void metadata(NodeMap map, Consumer<String> name, Consumer<String> description) {
        String nameValue = map.optionalString("name");
        if (nameValue != null) {
            map.at(map.requiredNode("name"), () -> {
                name.accept(nameValue);
                return null;
            });
        }
        String descriptionValue = map.optionalString("description");
        if (descriptionValue != null) {
            map.at(map.requiredNode("description"), () -> {
                description.accept(descriptionValue);
                return null;
            });
        }
    }

    private static Expected exactly(Class<?> context) {
        return context == null ? null : Expected.exactly(context);
    }

    private static String name(Class<?> type) {
        return type == null ? null : type.getName();
    }
}

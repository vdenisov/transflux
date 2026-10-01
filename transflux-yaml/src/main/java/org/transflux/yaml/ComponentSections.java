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
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.MapperDef;
import org.transflux.core.condition.Condition;
import org.transflux.yaml.DeclarationSites.Namespace;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The component sections of one document, each entry registered on the definition the document
 * contributes to: {@code steps}, {@code operations}, {@code choices}, {@code conditions},
 * {@code mappers}, {@code triggers} and {@code listeners}.
 */
final class ComponentSections {

    private final Classes classes;
    private final StateMachineDef<Object> machine;
    private final ConditionDescriptors conditions;
    private final ListenerEntries listeners;
    private final ActionEntries actions;
    private final TriggerEntries triggers;
    private final DeclarationSites sites;

    private ComponentSections(Readers readers, StateMachineDef<?> def) {
        this.classes = readers.classes();
        this.machine = TypeArguments.overObjects(def);
        this.sites = readers.sites();
        this.conditions = readers.conditions();
        this.listeners = readers.listeners();
        this.actions = readers.actions();
        this.triggers = readers.triggers();
    }

    /**
     * Registers every component a document declares.
     *
     * @param document the document's root mapping
     * @param def the definition the components are registered on
     * @param readers the readers this load shares
     *
     * @throws DefinitionLoadException when an entry is not valid, or the definition refuses it
     */
    static void read(NodeMap document, StateMachineDef<?> def, Readers readers) {
        ComponentSections sections = new ComponentSections(readers, def);
        int count = sections.section(document, "steps", "a step", sections.actions::registerStep)
            + sections.section(document, "operations", "an operation", sections.actions::registerOperation)
            + sections.section(document, "choices", "a choice", sections.actions::registerChoice)
            + sections.section(document, "conditions", "a condition", sections::condition)
            + sections.section(document, "mappers", "a mapper", sections::mapper)
            + sections.section(document, "triggers", "a trigger", sections.triggers::register)
            + sections.section(document, "listeners", "a listener", sections.listeners::register);
        Loggers.YAML_BINDING.debug("Component sections read, identifier={}, components={}",
            document.document().identifier(), count);
    }

    private int section(NodeMap document, String key, String what, BiConsumer<NodeMap, StateMachineDef<?>> entry) {
        List<Node> entries = document.optionalList(key);
        if (entries == null) {
            return 0;
        }
        entries.forEach(node -> entry.accept(NodeMap.of(document.document(), node, null, what), machine));
        return entries.size();
    }

    private void condition(NodeMap entry, StateMachineDef<?> def) {
        Class<Object> context = TypeArguments.overObjects(classes.optionalClass(entry, "context", null));
        conditions.declaration(entry, context, true, new ConditionDescriptors.Target() {
            @Override
            public void reference(String id) {
                throw new IllegalStateException("A registration is never a reference");
            }

            // One call per form, so each reaches the overload its static type selects.
            @Override
            public void expression(String id, String expression) {
                declare(id, () -> context == null
                    ? machine.condition(id, expression)
                    : machine.condition(id, context, expression));
            }

            @Override
            public void condition(String id, Condition<?, ?> condition) {
                Condition<Object, Object> typed = TypeArguments.overObjects(condition);
                declare(id, () -> context == null
                    ? machine.condition(id, typed)
                    : machine.condition(id, context, typed));
            }

            @Override
            public void predicate(String id, BiPredicate<?, ?> predicate) {
                BiPredicate<Object, Object> typed = TypeArguments.overObjects(predicate);
                declare(id, () -> context == null
                    ? machine.condition(id, typed)
                    : machine.condition(id, context, typed));
            }

            @Override
            public void predicate(String id, Predicate<?> predicate) {
                Predicate<Object> typed = TypeArguments.overObjects(predicate);
                declare(id, () -> context == null
                    ? machine.condition(id, typed)
                    : machine.condition(id, context, typed));
            }

            // The descriptor reader attributes a rejection to the condition's form, so only claim here.
            private void declare(String id, Supplier<?> call) {
                sites.claim(entry, entry.requiredNode("id"), Namespace.COMPONENT, id, call);
            }
        });
    }

    private void mapper(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredId("mapper");
        NodeMap within = entry.within("mapper '" + id + "'");
        Class<Object> parentType = TypeArguments.overObjects(classes.requiredClass(within, "parentType", null));
        Class<Object> childType = TypeArguments.overObjects(classes.requiredClass(within, "childType", null));
        ContextMapper<Object, Object> mapper = TypeArguments.overObjects(
            actions.mapper(within, Expected.exactly(parentType), Expected.exactly(childType)));
        Consumer<MapperDef<Object, Object>> configurer = mapperDef -> {
            metadata(within, mapperDef::withName, mapperDef::withDescription);
            mapperDef.using(mapper);
        };
        sites.declare(within, within.requiredNode("id"), Namespace.COMPONENT, id,
            () -> machine.mapperDef(id, parentType, childType, configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Mapper registered, id={}, parentType={}", id, parentType.getName());
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
}

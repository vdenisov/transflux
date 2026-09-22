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

import org.transflux.core.condition.Condition;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The condition descriptor grammar, at every position that takes a condition: a string references
 * a registered condition, a block declares one with exactly one of {@code class},
 * {@code predicate} or {@code expression}.
 */
final class ConditionDescriptors {

    /**
     * Where the conditions a position declares go; each position maps these onto its own def's
     * verbs.
     */
    interface Target {

        void reference(String id);

        /**
         * @param id the condition id, or {@code null} for an inline expression left to derive one
         * @param expression the expression
         */
        void expression(String id, String expression);

        void condition(String id, Condition<?, ?> condition);

        void predicate(String id, BiPredicate<?, ?> predicate);

        void predicate(String id, Predicate<?> predicate);

        /**
         * A target made of one def verb per form, each passed as a method reference so it reaches
         * the overload its static type selects.
         *
         * @param reference attaches a registered condition by id
         * @param anonymous declares an expression that derives its own id
         * @param expression declares an expression under an id
         * @param condition declares a condition instance
         * @param bi declares a two-argument predicate
         * @param mono declares a one-argument predicate
         *
         * @return the target
         */
        @SuppressWarnings("rawtypes")
        static Target of(Consumer<String> reference, Consumer<String> anonymous, BiConsumer<String, String> expression,
                         BiConsumer<String, Condition> condition, BiConsumer<String, BiPredicate> bi,
                         BiConsumer<String, Predicate> mono) {
            return new Target() {
                @Override
                public void reference(String id) {
                    reference.accept(id);
                }

                @Override
                public void expression(String id, String text) {
                    if (id == null) {
                        anonymous.accept(text);
                    } else {
                        expression.accept(id, text);
                    }
                }

                @Override
                public void condition(String id, Condition<?, ?> instance) {
                    condition.accept(id, instance);
                }

                @Override
                public void predicate(String id, BiPredicate<?, ?> predicate) {
                    bi.accept(id, predicate);
                }

                @Override
                public void predicate(String id, Predicate<?> predicate) {
                    mono.accept(id, predicate);
                }
            };
        }
    }

    private final Classes classes;
    private final Class<?> entityType;

    ConditionDescriptors(Classes classes, Class<?> entityType) {
        this.classes = classes;
        this.entityType = entityType;
    }

    /**
     * Reads a list of descriptors, such as {@code preConditions}.
     *
     * @param owner the mapping holding the list
     * @param key the list's key
     * @param context the context the conditions run against; {@code null} leaves it unchecked
     * @param target where the conditions go
     *
     * @throws DefinitionLoadException when an entry is not a descriptor
     */
    void list(NodeMap owner, String key, Class<?> context, Target target) {
        List<Node> entries = owner.optionalList(key);
        if (entries != null) {
            entries.forEach(entry -> descriptor(owner, entry, context, target));
        }
    }

    /**
     * Reads one descriptor.
     *
     * @param owner the mapping the descriptor sits in
     * @param node the descriptor
     * @param context the context the condition runs against; {@code null} leaves it unchecked
     * @param target where the condition goes
     *
     * @throws DefinitionLoadException when the node is not a descriptor
     */
    void descriptor(NodeMap owner, Node node, Class<?> context, Target target) {
        if (node instanceof ScalarNode reference) {
            owner.at(node, () -> {
                target.reference(reference.getValue());
                return null;
            });
            return;
        }
        declaration(NodeMap.of(owner.document(), node, owner.declarationPath(), "a condition"), context, false, target);
    }

    /**
     * Reads a condition declared as a block, inline or registered.
     *
     * @param block the block
     * @param context the context the condition runs against; {@code null} leaves it unchecked
     * @param idRequired whether an expression needs an id too, as a registration does
     * @param target where the condition goes
     *
     * @throws DefinitionLoadException when the block does not declare a condition
     */
    void declaration(NodeMap block, Class<?> context, boolean idRequired, Target target) {
        String form = block.exactlyOneOf("class", "predicate", "expression");
        String id = form.equals("expression") && !idRequired ? block.optionalString("id") : block.requiredString("id");
        NodeMap within = block.within(id == null ? "condition" : "condition '" + id + "'");
        Node at = within.requiredNode(form);

        switch (form) {
            case "class" -> {
                Condition<?, ?> condition = classes.instantiate(within, form, Condition.class,
                    Expected.superOf(entityType), exactly(context));
                within.at(at, () -> {
                    target.condition(id, condition);
                    return null;
                });
            }
            case "predicate" -> {
                Object predicate = predicate(within, form,
                    new Expected[] {Expected.superOf(entityType), exactly(context)}, Expected.superOf(entityType));
                within.at(at, () -> {
                    if (predicate instanceof BiPredicate<?, ?> bi) {
                        target.predicate(id, bi);
                    } else {
                        target.predicate(id, (Predicate<?>) predicate);
                    }
                    return null;
                });
            }
            default -> {
                String expression = within.requiredString(form);
                within.at(at, () -> {
                    target.expression(id, expression);
                    return null;
                });
            }
        }
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Condition declared, id={}, form={}", id, form);
    }

    /**
     * Instantiates a class that is a {@code BiPredicate} or a {@code Predicate}, and not both - a
     * Java host could not pass such a class to either overload.
     *
     * @param map the mapping holding the key
     * @param key the key naming the class
     * @param bi the type arguments a {@code BiPredicate} must declare
     * @param mono the type argument a {@code Predicate} must declare
     *
     * @return the instance, a {@link BiPredicate} or a {@link Predicate}
     *
     * @throws DefinitionLoadException when the class is neither, is both, or cannot be instantiated
     */
    Object predicate(NodeMap map, String key, Expected[] bi, Expected mono) {
        Class<?> type = classes.requiredClass(map, key, null);
        boolean isBi = BiPredicate.class.isAssignableFrom(type);
        boolean isMono = Predicate.class.isAssignableFrom(type);
        if (isBi == isMono) {
            throw map.error(map.requiredNode(key), "class " + type.getName() + (isBi
                ? " implements both BiPredicate and Predicate; implement one"
                : " is neither a BiPredicate nor a Predicate"));
        }
        return isBi
            ? classes.instantiate(map, key, type, BiPredicate.class, bi)
            : classes.instantiate(map, key, type, Predicate.class, mono);
    }

    private static Expected exactly(Class<?> context) {
        return context == null ? null : Expected.exactly(context);
    }
}

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

import org.transflux.core.ListenerDef;
import org.transflux.core.StateMachineDef;
import org.transflux.core.action.ActionListener;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.state.StateListener;
import org.transflux.core.transition.TransitionListener;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Listeners as a document writes them: an entry in the {@code listeners:} pool, an owner's
 * {@code listeners:} block keyed by its hooks, and {@code disableGlobalListeners:}.
 */
final class ListenerEntries {

    /** The three listener categories, each with the interface its classes implement. */
    enum Category {
        STATE(StateListener.class),
        TRANSITION(TransitionListener.class),
        ACTION(ActionListener.class);

        private final Class<?> type;

        Category(Class<?> type) {
            this.type = type;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One hook of an owner.
     *
     * @param name the hook's key, such as {@code onStart}
     * @param category the category every listener under the hook belongs to
     * @param reference attaches a listener declared elsewhere by id
     * @param declaration declares a listener in place under an id, configured by the consumer
     */
    record Hook(String name, Category category, Consumer<String> reference,
                BiConsumer<String, Consumer<ListenerDef<?, ?>>> declaration) {
    }

    private static final String DISABLE_KEY = "disableGlobalListeners";

    private final Classes classes;
    private final Class<?> entityType;
    private final DeclarationSites sites;

    ListenerEntries(Classes classes, Class<?> entityType, DeclarationSites sites) {
        this.classes = classes;
        this.entityType = entityType;
        this.sites = sites;
    }

    /**
     * Registers one entry of the {@code listeners:} pool, under the category its class implements.
     *
     * @param entry the entry
     * @param def the definition it is registered on
     *
     * @throws DefinitionLoadException when the entry is not a listener registration
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void register(NodeMap entry, StateMachineDef<?> def) {
        String id = entry.requiredId("listener");
        NodeMap within = entry.within("listener '" + id + "'");
        Class<?> type = classes.requiredClass(within, "class", null);
        Category category = category(within, type);
        Class<?> context = classes.optionalClass(within, "context", null);
        if (category == Category.STATE && context != null) {
            throw within.error(within.requiredNode("context"),
                "a state listener takes no context: it is handed whichever context the transition carries");
        }

        Consumer configurer = (Consumer<ListenerDef<?, ?>>) listener ->
            configure(within, listener, type, category, context);
        sites.declare(within, within.requiredNode("id"), DeclarationSites.Namespace.LISTENER, id, () ->
            switch (category) {
                case STATE -> def.stateListener(id, configurer);
                case TRANSITION -> context == null
                    ? def.transitionListener(id, configurer)
                    : def.transitionListener(id, context, configurer);
                case ACTION -> context == null
                    ? def.actionListener(id, configurer)
                    : def.actionListener(id, context, configurer);
            });
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Listener registered, id={}, category={}", id, category.label());
    }

    /**
     * Reads an owner's {@code listeners:} block.
     *
     * @param owner the owner's mapping
     * @param context the owner's context; {@code null} for {@code Object}
     * @param claimsIds whether the owner claims a listener declared in place when it is declared,
     *        as a state and the state machine do; a transition and an action claim theirs at build
     * @param hooks the owner's hooks, which are the keys the block allows
     *
     * @throws DefinitionLoadException when an entry is neither a reference nor a declaration
     */
    void hooks(NodeMap owner, Class<?> context, boolean claimsIds, List<Hook> hooks) {
        NodeMap block = owner.optionalMap("listeners");
        if (block == null) {
            return;
        }
        for (Hook hook : hooks) {
            List<Node> entries = block.optionalList(hook.name());
            if (entries != null) {
                entries.forEach(entry -> attach(block, entry, hook, context, claimsIds));
            }
        }
        block.rejectUnknownKeys();
    }

    /**
     * Reads {@code disableGlobalListeners:}: {@code true} for all of them, or a list of ids.
     *
     * @param owner the owner's mapping
     * @param all disables every state-machine-wide listener of the owner's category
     * @param some disables the listeners named
     *
     * @throws DefinitionLoadException when the value is neither
     */
    static void disables(NodeMap owner, Runnable all, Consumer<String[]> some) {
        Node node = owner.optionalNode(DISABLE_KEY);
        if (node == null) {
            return;
        }
        if (node instanceof ScalarNode scalar && scalar.getValue().equals("true")) {
            owner.at(node, () -> {
                all.run();
                return null;
            });
            return;
        }
        if (!(node instanceof SequenceNode sequence)) {
            throw owner.error(node, "'" + DISABLE_KEY + "' must be true or a list of listener ids");
        }
        if (sequence.getValue().isEmpty()) {
            // Core's own refusal names the Java call; a document spells the same intent 'true'.
            throw owner.error(node, "'" + DISABLE_KEY + "' names no listener; write 'true' to disable every global"
                + " listener");
        }
        String[] ids = sequence.getValue().stream().map(id -> {
            if (!(id instanceof ScalarNode scalar)) {
                throw owner.error(id, "'" + DISABLE_KEY + "' must list listener ids");
            }
            return scalar.getValue();
        }).toArray(String[]::new);
        owner.at(node, () -> {
            some.accept(ids);
            return null;
        });
    }

    private void attach(NodeMap block, Node entry, Hook hook, Class<?> context, boolean claimsIds) {
        Category category = hook.category();
        if (entry instanceof ScalarNode reference) {
            block.at(entry, () -> {
                hook.reference().accept(reference.getValue());
                return null;
            });
            return;
        }
        NodeMap declaration = NodeMap.of(block.document(), entry, block.declarationPath(), "a listener");
        String id = declaration.requiredId("listener");
        NodeMap within = declaration.within("listener '" + id + "'");
        Class<?> type = classes.requiredClass(within, "class", category.type);
        Supplier<Object> declare = () -> {
            hook.declaration().accept(id, listener -> configure(within, listener, type, category, context));
            return null;
        };
        // Only an id core claims now can collide now; recording the others could point a later
        // rejection at a declaration it did not collide with.
        if (claimsIds) {
            sites.declare(within, within.requiredNode("id"), DeclarationSites.Namespace.LISTENER, id, declare);
        } else {
            within.at(within.requiredNode("id"), declare);
        }
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Listener declared, id={}, hook={}", id, hook.name());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configure(NodeMap map, ListenerDef listener, Class<?> type, Category category, Class<?> context) {
        ComponentSections.metadata(map, listener::withName, listener::withDescription);

        Object instance = category == Category.STATE
            ? classes.instantiate(map, "class", type, category.type, Expected.superOf(entityType))
            : classes.instantiate(map, "class", type, category.type,
                Expected.superOf(entityType), Expected.exactly(context == null ? Object.class : context));
        map.at(map.requiredNode("class"), () -> listener.using(instance));

        Boolean async = map.optionalBoolean("async");
        AsyncRejectionPolicy policy = map.optionalEnum("onRejection", AsyncRejectionPolicy.class);
        if (policy != null && !Boolean.TRUE.equals(async)) {
            throw map.error(map.requiredNode("onRejection"),
                "'onRejection' applies to an async listener; add 'async: true'");
        }
        if (Boolean.TRUE.equals(async)) {
            if (policy == null) {
                map.at(map.requiredNode("async"), listener::withAsync);
            } else {
                map.at(map.requiredNode("onRejection"), () -> listener.withAsync(policy));
            }
        }
    }

    private static Category category(NodeMap map, Class<?> type) {
        List<Category> implemented = Arrays.stream(Category.values())
            .filter(category -> category.type.isAssignableFrom(type))
            .toList();
        String declared = map.optionalString("type");
        if (declared != null) {
            Category category = Arrays.stream(Category.values())
                .filter(candidate -> candidate.label().equals(declared))
                .findFirst()
                .orElseThrow(() -> map.error(map.requiredNode("type"),
                    "'type' must be one of state, transition, action, not '" + declared + "'"));
            if (!implemented.contains(category)) {
                throw map.error(map.requiredNode("type"), "class " + type.getName() + " is not a "
                    + category.type.getName());
            }
            return category;
        }
        if (implemented.size() == 1) {
            return implemented.get(0);
        }
        if (implemented.isEmpty()) {
            throw map.error(map.requiredNode("class"), "class " + type.getName()
                + " is not a StateListener, TransitionListener or ActionListener");
        }
        List<String> labels = implemented.stream().map(Category::label).toList();
        throw map.error(map.requiredNode("class"), "class " + type.getName() + " implements the "
            + String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1)
            + " listener interfaces; say which this registration is with 'type'");
    }
}

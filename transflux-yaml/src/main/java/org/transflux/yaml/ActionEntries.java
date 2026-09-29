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
import org.transflux.core.action.ActionSequence;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.BranchDef;
import org.transflux.core.action.ChoiceDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.DefaultBranchDef;
import org.transflux.core.action.NoMatchBehavior;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.StepDef;
import org.transflux.yaml.ConditionDescriptors.Target;
import org.transflux.yaml.DeclarationSites.Namespace;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.util.List;
import java.util.function.Consumer;

/**
 * Actions as a document writes them: an entry in the {@code steps:}, {@code operations:} or
 * {@code choices:} pool, and an entry in an {@code actions:} list, which references an action with
 * {@code run:} or declares one in place with {@code step:}, {@code operation:} or {@code choice:}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class ActionEntries {

    private static final List<String> VERBS = List.of("run", "step", "operation", "choice");

    private final Classes classes;
    private final Class<?> entityType;
    private final ConditionDescriptors conditions;
    private final ActionKeys actionKeys;
    private final DeclarationSites sites;

    ActionEntries(Classes classes, Class<?> entityType, ConditionDescriptors conditions, ActionKeys actionKeys,
                  DeclarationSites sites) {
        this.classes = classes;
        this.entityType = entityType;
        this.conditions = conditions;
        this.actionKeys = actionKeys;
        this.sites = sites;
    }

    /**
     * Registers one entry of the {@code steps:} pool.
     *
     * @param entry the entry
     * @param def the definition it is registered on
     *
     * @throws DefinitionLoadException when the entry is not a step registration
     */
    void registerStep(NodeMap entry, StateMachineDef def) {
        String id = entry.requiredId("step");
        NodeMap within = entry.within("step '" + id + "'");
        Class<?> context = classes.optionalClass(within, "context", null);
        Consumer<StepDef> configurer = step -> step(within, step, context);
        sites.declare(within, within.requiredNode("id"), Namespace.COMPONENT, id, () -> context == null
            ? def.step(id, (Consumer) configurer)
            : def.step(id, context, (Consumer) configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Step registered, id={}, context={}", id, name(context));
    }

    /**
     * Registers one entry of the {@code operations:} pool.
     *
     * @param entry the entry
     * @param def the definition it is registered on
     *
     * @throws DefinitionLoadException when the entry is not an operation registration
     */
    void registerOperation(NodeMap entry, StateMachineDef def) {
        String id = entry.requiredId("operation");
        NodeMap within = entry.within("operation '" + id + "'");
        Class<?> context = registeredContext(within);
        Consumer<OperationDef> configurer = operation -> operation(within, operation, context);
        sites.declare(within, within.requiredNode("id"), Namespace.COMPONENT, id,
            () -> def.operation(id, context, (Consumer) configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Operation registered, id={}, context={}", id, context.getName());
    }

    /**
     * Registers one entry of the {@code choices:} pool.
     *
     * @param entry the entry
     * @param def the definition it is registered on
     *
     * @throws DefinitionLoadException when the entry is not a choice registration
     */
    void registerChoice(NodeMap entry, StateMachineDef def) {
        String id = entry.requiredId("choice");
        NodeMap within = entry.within("choice '" + id + "'");
        Class<?> context = registeredContext(within);
        Consumer<ChoiceDef> configurer = choice -> choice(within, id, choice, context);
        sites.declare(within, within.requiredNode("id"), Namespace.COMPONENT, id,
            () -> def.choice(id, context, (Consumer) configurer));
        within.rejectUnknownKeys();
        Loggers.YAML_BINDING.debug("Choice registered, id={}, context={}", id, context.getName());
    }

    /**
     * Reads an optional {@code actions:} list onto a sequence, such as a transition's body.
     *
     * @param owner the mapping holding the list
     * @param sequence the sequence's def, inside its configurer
     * @param context the context the sequence's members run against
     *
     * @throws DefinitionLoadException when an entry is neither a reference nor a declaration
     */
    void list(NodeMap owner, ActionSequence<?, ?, ?> sequence, Class<?> context) {
        List<Node> entries = owner.optionalList("actions");
        if (entries != null) {
            entries.forEach(entry -> member(owner, entry, sequence, context));
        }
    }

    /**
     * Reads a mapper block - a class, or {@code mapTo} with an optional {@code mapFrom} - leaving
     * the block's other keys to its caller.
     *
     * @param block the block
     * @param parent what the class must declare as its parent type; {@code null} leaves it unchecked
     * @param child what the class must declare as its child type; {@code null} leaves it unchecked
     *
     * @return the mapper
     *
     * @throws DefinitionLoadException when the block holds neither or both forms, or either is
     *         not valid
     */
    ContextMapper<?, ?> mapper(NodeMap block, Expected parent, Expected child) {
        if (block.exactlyOneOf("class", "mapTo").equals("class")) {
            if (block.optionalNode("mapFrom") != null) {
                throw block.error(block.keyNode("mapFrom"), "'mapFrom' needs 'mapTo' beside it; a class maps back itself");
            }
            return classes.instantiate(block, "class", ContextMapper.class, parent, child);
        }
        return Expressions.mapper(block);
    }

    private void member(NodeMap owner, Node node, ActionSequence sequence, Class<?> context) {
        NodeMap entry = NodeMap.of(owner.document(), node, owner.declarationPath(), "an action entry");
        String verb = entry.exactlyOneOf(VERBS.toArray(String[]::new));
        String id = entry.requiredString(verb);
        NodeMap within = entry.within(verb + " '" + id + "'");
        if (verb.equals("run")) {
            reference(within, sequence, id, context);
        } else {
            declaration(within, verb, sequence, id, context);
        }
        within.rejectUnknownKeys();
    }

    private void reference(NodeMap within, ActionSequence sequence, String id, Class<?> context) {
        // A reference carries its callee's listeners, so the keys belong on the callee's declaration.
        for (String key : List.of("listeners", "disableGlobalListeners")) {
            if (within.holds(key)) {
                throw within.error(within.keyNode(key), "'" + key + "' belongs on the declaration of action '" + id
                    + "'; a run: entry carries its callee's listeners with it");
            }
        }
        boolean forked = Boolean.TRUE.equals(within.optionalBoolean("fork"));
        Node mapper = within.optionalNode("mapper");
        Object resolved = mapper == null ? null
            : callSiteMapper(within, mapper, forked, Expected.exactly(context), null);
        AsyncRejectionPolicy policy = within.optionalEnum("onRejection", AsyncRejectionPolicy.class);
        if (policy != null && !forked) {
            throw within.error(within.requiredNode("onRejection"),
                "'onRejection' applies to a forked reference; add 'fork: true'");
        }

        within.at(within.requiredNode("run"), () -> {
            if (!forked) {
                run(sequence, id, resolved);
            } else if (policy == null) {
                fork(sequence, id, resolved);
            } else {
                fork(sequence, id, resolved, policy);
            }
            return null;
        });
        Loggers.YAML_BINDING.debug("Action referenced, id={}, forked={}, mapper={}", id, forked, mapperLabel(resolved));
    }

    private void declaration(NodeMap within, String verb, ActionSequence sequence, String id, Class<?> enclosing) {
        Class<?> declared = classes.optionalClass(within, "context", null);
        Node mapper = within.optionalNode("mapper");
        if (mapper != null && declared == null) {
            throw within.error(within.keyNode("mapper"),
                "'mapper' produces the context this declaration runs against; name it with 'context'");
        }
        boolean forked = Boolean.TRUE.equals(within.optionalBoolean("fork"));
        Object resolved = mapper == null ? null
            : callSiteMapper(within, mapper, forked, Expected.exactly(enclosing), Expected.exactly(declared));
        // A declaration naming no context of its own inherits the enclosing one.
        Class<?> context = declared == null ? enclosing : declared;

        within.at(within.requiredNode(verb), () -> {
            switch (verb) {
                case "step" -> declareStep(sequence, forked, id, declared, resolved,
                    (Consumer<StepDef>) step -> step(within, step, context));
                case "operation" -> declareOperation(sequence, forked, id, declared, resolved,
                    (Consumer<OperationDef>) operation -> operation(within, operation, context));
                default -> declareChoice(sequence, forked, id, declared, resolved,
                    (Consumer<ChoiceDef>) choice -> choice(within, id, choice, context));
            }
            return null;
        });
        if (Loggers.YAML_BINDING.isDebugEnabled()) {
            Loggers.YAML_BINDING.debug("Action declared, id={}, form={}, forked={}, context={}, mapper={}",
                id, verb, forked, declared == null ? "inherited" : declared.getName(), mapperLabel(resolved));
        }
    }

    /**
     * Reads a call site's {@code mapper:} - a registered mapper's id, or a block declaring one.
     *
     * @param within the entry holding the key
     * @param mapper the key's value
     * @param forked whether the entry is forked, where a written write-back could never run
     * @param parent what a mapper class must declare as its parent type; {@code null} leaves it unchecked
     * @param child what a mapper class must declare as its child type; {@code null} leaves it unchecked
     *
     * @return the registered mapper's id, or the mapper the block declares
     *
     * @throws DefinitionLoadException when the block is not a mapper, or writes back at a fork
     */
    private Object callSiteMapper(NodeMap within, Node mapper, boolean forked, Expected parent, Expected child) {
        if (mapper instanceof ScalarNode) {
            return within.requiredString("mapper");
        }
        NodeMap block = within.requiredMap("mapper").within("mapper");
        // A registered or class mapper's write-back is invisible here; one written beside the fork is not.
        if (forked && block.optionalNode("mapFrom") != null) {
            throw block.error(block.keyNode("mapFrom"),
                "'mapFrom' never runs at a forked call site; a forked member cannot write back");
        }
        ContextMapper<?, ?> instance = mapper(block, parent, child);
        block.rejectUnknownKeys();
        return instance;
    }

    /**
     * @param mapper a call site's mapper as {@link #callSiteMapper} resolved it; {@code null} for none
     *
     * @return how the log names it: {@code none}, the registered mapper's id, or {@code inline}
     */
    private static String mapperLabel(Object mapper) {
        if (mapper == null) {
            return "none";
        }
        return mapper instanceof String mapperId ? mapperId : "inline";
    }

    private void step(NodeMap within, StepDef step, Class<?> context) {
        Action action = classes.instantiate(within, "class", Action.class,
            Expected.superOf(entityType), context == null ? null : Expected.exactly(context));
        within.at(within.requiredNode("class"), () -> step.using(action));
        actionKeys.apply(within, step, context);
    }

    private void operation(NodeMap within, OperationDef operation, Class<?> context) {
        actionKeys.apply(within, operation, context);
        members(within, operation, context);
    }

    private void choice(NodeMap within, String choiceId, ChoiceDef choice, Class<?> context) {
        actionKeys.apply(within, choice, context);
        for (Node node : within.requiredList("branches")) {
            NodeMap entry = NodeMap.of(within.document(), node, within.declarationPath(), "a branch");
            String id = entry.requiredId("branch");
            NodeMap branch = entry.within("branch '" + id + "'");
            Consumer<BranchDef> configurer = def -> {
                conditions.descriptor(branch, branch.requiredNode("condition"), context, Target.of(
                    def::condition, def::conditionExpression, def::condition,
                    def::condition, def::condition, def::condition));
                members(branch, def, context);
            };
            branch.at(branch.requiredNode("id"), () -> choice.branch(id, (Consumer) configurer));
            branch.rejectUnknownKeys();
            Loggers.YAML_BINDING.debug("Branch declared, choiceId={}, branchId={}", choiceId, id);
        }

        NodeMap fallback = within.optionalMap("default");
        if (fallback != null) {
            NodeMap branch = fallback.within("default branch");
            Consumer<DefaultBranchDef> configurer = def -> members(branch, def, context);
            within.at(within.requiredNode("default"), () -> choice.defaultBranch((Consumer) configurer));
            branch.rejectUnknownKeys();
            Loggers.YAML_BINDING.debug("Default branch declared, choiceId={}", choiceId);
        }

        NoMatchBehavior onNoMatch = within.optionalEnum("onNoMatch", NoMatchBehavior.class);
        if (onNoMatch != null) {
            within.at(within.requiredNode("onNoMatch"), () -> choice.onNoMatch(onNoMatch));
        }
    }

    /**
     * Reads the {@code actions:} list a container or a branch must carry.
     *
     * @param owner the mapping holding the list
     * @param sequence the container's or branch's def, inside its configurer
     * @param context the context the members run against
     *
     * @throws DefinitionLoadException when the list is absent or an entry is not valid
     */
    private void members(NodeMap owner, ActionSequence sequence, Class<?> context) {
        owner.requiredList("actions").forEach(entry -> member(owner, entry, sequence, context));
    }

    private Class<?> registeredContext(NodeMap within) {
        Class<?> declared = classes.optionalClass(within, "context", null);
        // A registration naming no context is registered against Object, as the untyped Java one is.
        return declared == null ? Object.class : declared;
    }

    // One call per shape below, so each reaches the overload its static types select.

    private static void run(ActionSequence sequence, String id, Object mapper) {
        if (mapper == null) {
            sequence.run(id);
        } else if (mapper instanceof String mapperId) {
            sequence.run(id, mapperId);
        } else {
            sequence.run(id, (ContextMapper) mapper);
        }
    }

    private static void fork(ActionSequence sequence, String id, Object mapper) {
        if (mapper == null) {
            sequence.fork(id);
        } else if (mapper instanceof String mapperId) {
            sequence.fork(id, mapperId);
        } else {
            sequence.fork(id, (ContextMapper) mapper);
        }
    }

    private static void fork(ActionSequence sequence, String id, Object mapper, AsyncRejectionPolicy policy) {
        if (mapper == null) {
            sequence.fork(id, policy);
        } else if (mapper instanceof String mapperId) {
            sequence.fork(id, mapperId, policy);
        } else {
            sequence.fork(id, (ContextMapper) mapper, policy);
        }
    }

    private static void declareStep(ActionSequence sequence, boolean forked, String id, Class<?> context,
                                    Object mapper, Consumer body) {
        if (context == null) {
            if (forked) {
                sequence.forkStep(id, body);
            } else {
                sequence.step(id, body);
            }
        } else if (mapper == null) {
            if (forked) {
                sequence.forkStep(id, context, body);
            } else {
                sequence.step(id, context, body);
            }
        } else if (mapper instanceof String mapperId) {
            if (forked) {
                sequence.forkStep(id, context, mapperId, body);
            } else {
                sequence.step(id, context, mapperId, body);
            }
        } else if (forked) {
            sequence.forkStep(id, context, (ContextMapper) mapper, body);
        } else {
            sequence.step(id, context, (ContextMapper) mapper, body);
        }
    }

    private static void declareOperation(ActionSequence sequence, boolean forked, String id, Class<?> context,
                                         Object mapper, Consumer body) {
        if (context == null) {
            if (forked) {
                sequence.forkOperation(id, body);
            } else {
                sequence.operation(id, body);
            }
        } else if (mapper == null) {
            if (forked) {
                sequence.forkOperation(id, context, body);
            } else {
                sequence.operation(id, context, body);
            }
        } else if (mapper instanceof String mapperId) {
            if (forked) {
                sequence.forkOperation(id, context, mapperId, body);
            } else {
                sequence.operation(id, context, mapperId, body);
            }
        } else if (forked) {
            sequence.forkOperation(id, context, (ContextMapper) mapper, body);
        } else {
            sequence.operation(id, context, (ContextMapper) mapper, body);
        }
    }

    private static void declareChoice(ActionSequence sequence, boolean forked, String id, Class<?> context,
                                      Object mapper, Consumer body) {
        if (context == null) {
            if (forked) {
                sequence.forkChoice(id, body);
            } else {
                sequence.choice(id, body);
            }
        } else if (mapper == null) {
            if (forked) {
                sequence.forkChoice(id, context, body);
            } else {
                sequence.choice(id, context, body);
            }
        } else if (mapper instanceof String mapperId) {
            if (forked) {
                sequence.forkChoice(id, context, mapperId, body);
            } else {
                sequence.choice(id, context, mapperId, body);
            }
        } else if (forked) {
            sequence.forkChoice(id, context, (ContextMapper) mapper, body);
        } else {
            sequence.choice(id, context, (ContextMapper) mapper, body);
        }
    }

    private static String name(Class<?> type) {
        return type == null ? null : type.getName();
    }
}

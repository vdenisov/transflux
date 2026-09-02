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

import org.transflux.core.action.Action;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.action.OperationDef;
import org.transflux.core.action.StepDef;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Shared implementation and storage for the member grammar every ordered action list exposes -
 * a declarative container, a conditional's branch, and its default branch.
 * <p>
 * Every owning def declares one sink and implements its public methods as one-line delegates, so
 * validation order, argument labels and the configurer guard are written once. The owners have no
 * closer common ancestor than {@link ConfigurableDefImpl} to hang this on: {@code OperationDefImpl}
 * extends the sealed {@code ActionDefImpl}, while the two branch defs - not being actions - extend
 * {@code ConfigurableDefImpl} directly. That base supplies
 * everything member declaration needs - the configurer guard, the def label, and
 * {@link ConfigurableDefImpl#runConfigurer} - and nothing more.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the enclosing sequence runs against
 * @param <D> the def interface returned by every overload, for fluent chaining
 */
final class ActionSequenceSink<T, C, D> {

    private final ConfigurableDefImpl owner;
    private final D self;

    private final List<DeclaredMember<T, C>> members = new ArrayList<>();

    /**
     * Creates a sink for one member list.
     *
     * @param owner the def whose configurer guard gates every declaration
     * @param self the value returned by every overload
     */
    ActionSequenceSink(ConfigurableDefImpl owner, D self) {
        this.owner = owner;
        this.self = self;
    }

    D run(String id) {
        return reference("run", id, false);
    }

    D run(String id, String mapperId) {
        return reference("run", id, mapperId, false);
    }

    D run(String id, ContextMapper<C, ?> inlineMapper) {
        return reference("run", id, inlineMapper, false);
    }

    D fork(String id) {
        return reference("fork", id, true);
    }

    D fork(String id, String mapperId) {
        return reference("fork", id, mapperId, true);
    }

    D fork(String id, ContextMapper<C, ?> inlineMapper) {
        return reference("fork", id, inlineMapper, true);
    }

    D step(String id, Action<T, C> action) {
        owner.requireConfigurerActive("step");
        members.add(new DeclaredMember<>(ActionRef.inline(id, action, ActionKind.STEP), false));
        return self;
    }

    D step(String id, Consumer<StepDef<T, C>> configurer) {
        owner.requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(configurer, "Step configurer");
        StepDefImpl<T, C> def = new StepDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.inline(id, def), false));
        return self;
    }

    <N> D step(String id, Class<N> contextType, MapperRef mapperRef, Action<T, N> action) {
        return typedStep(id, contextType, mapperRef, def -> def.using(action));
    }

    <N> D step(String id, Class<N> contextType, MapperRef mapperRef,
               Consumer<StepDef<T, N>> configurer) {
        requireNotNull(configurer, "Step configurer");
        return typedStep(id, contextType, mapperRef, configurer);
    }

    D conditional(String id, Consumer<ConditionalOperationDef<T, C>> configurer) {
        owner.requireConfigurerActive("conditional");
        requireNotBlank(id, "Conditional operation ID");
        requireNotNull(configurer, "Conditional configurer");

        ConditionalOperationDefImpl<T, C> def = new ConditionalOperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.conditional(id, def), false));

        return self;
    }

    <N> D conditional(String id, Class<N> contextType, MapperRef mapperRef,
                      Consumer<ConditionalOperationDef<T, N>> configurer) {
        owner.requireConfigurerActive("conditional");
        requireNotBlank(id, "Conditional operation ID");
        requireNotNull(contextType, "Conditional context type");
        requireNotNull(configurer, "Conditional configurer");

        ConditionalOperationDefImpl<T, N> def = new ConditionalOperationDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.conditional(id, erase(def), mapperRef), false));

        return self;
    }

    D operation(String id, Consumer<OperationDef<T, C>> configurer) {
        owner.requireConfigurerActive("operation");
        requireNotBlank(id, "Operation ID");
        requireNotNull(configurer, "Operation configurer");

        OperationDefImpl<T, C> def = new OperationDefImpl<>(id);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.operation(id, def), false));

        return self;
    }

    <N> D operation(String id, Class<N> contextType, MapperRef mapperRef,
                    Consumer<OperationDef<T, N>> configurer) {
        owner.requireConfigurerActive("operation");
        requireNotBlank(id, "Operation ID");
        requireNotNull(contextType, "Operation context type");
        requireNotNull(configurer, "Operation configurer");

        OperationDefImpl<T, N> def = new OperationDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.operation(id, erase(def), mapperRef), false));

        return self;
    }

    private <N> D typedStep(String id, Class<N> contextType, MapperRef mapperRef,
                            Consumer<StepDef<T, N>> configurer) {
        owner.requireConfigurerActive("step");
        requireNotBlank(id, "Step ID");
        requireNotNull(contextType, "Step context type");

        StepDefImpl<T, N> def = new StepDefImpl<>(id, contextType);
        ConfigurableDefImpl.runConfigurer(def, configurer);
        members.add(new DeclaredMember<>(ActionRef.inline(id, erase(def), mapperRef), false));

        return self;
    }

    /**
     * Retypes a def declared against its own context so it can sit in this sequence's member list.
     * <p>
     * The member list is typed against the enclosing {@code C}, but a member that declares a
     * context runs against that one instead - the same situation a by-id reference to a component
     * with a different context is already in, and resolved the same way: the
     * {@link ResolvedContextMapping} bridges the two at dispatch, and the cast is confined to the
     * declaration site.
     */
    @SuppressWarnings("unchecked")
    private static <T, C, N, X extends ActionDefImpl<T, ?, ?>> X erase(ActionDefImpl<T, N, ?> def) {
        return (X) def;
    }

    /**
     * Returns the declared members in declaration order.
     *
     * @return an unmodifiable view of the member list
     */
    List<DeclaredMember<T, C>> members() {
        return Collections.unmodifiableList(members);
    }

    /**
     * Visits every member of this sequence and, recursively, every member nested inside one -
     * a conditional member's branches and their own nested conditionals.
     * <p>
     * The walk terminates by construction: the edge set is the source nesting, every
     * {@code conditional(...)} and {@code branch(...)} constructs a fresh def, no DSL method
     * accepts an already-built one, and the configurer guard makes a def inert once its lambda
     * returns - so the structure is a finite tree rather than a graph. Only a by-id reference
     * can close a loop, and this walk does not follow one.
     *
     * @param visitor receives each member, in declaration order, outermost first
     */
    void visitAllMembers(Consumer<DeclaredMember<T, C>> visitor) {
        for (DeclaredMember<T, C> member : members) {
            visitor.accept(member);
            member.ref().visitNestedMembers(visitor);
        }
    }

    /**
     * Walks this sequence's action refs and forwards each to the supplied sink. By-id refs
     * no-op; inline refs push themselves; conditional refs recurse into their branches and then
     * register their own bound action.
     *
     * @param sink receives each inline declaration
     */
    void collectInlineRegistrations(InlineRegistrationSink<T, C> sink) {
        for (DeclaredMember<T, C> member : members) {
            member.ref().collectInlineRegistrations(sink);
        }
    }

    /**
     * Reports the listener ids declared on any def carried by a member of this sequence.
     *
     * @param sink receives {@code (listenerId, ownerLabel)} for each declared listener
     */
    void collectListenerIds(BiConsumer<String, String> sink) {
        for (DeclaredMember<T, C> member : members) {
            member.ref().collectListenerIds(sink);
        }
    }

    /**
     * Records the context each inline declaration in this sequence runs against, descending into
     * the two forms that hold members of their own.
     * <p>
     * A declaration that names a context reports that one, {@code Object} included - an action
     * written against {@code Object} ignores the context, so a reference to it passes through from
     * any caller, which is the rule registered components already follow. One that names none
     * reports the enclosing sequence's, which is what it will actually be handed. What it hands
     * <em>down</em> is a different question, and {@link ActionDefImpl#subtreeContext} answers it -
     * the same method the reference check uses, so the two cannot disagree.
     *
     * @param scopeContext the context this sequence's members run against; {@code null} is read as
     *                     {@code Object}
     * @param declaringScope the id of the scope these declarations register into - the enclosing
     *                       container's, or the conditional's when this is one of its branches
     * @param sink receives each inline declaration, with the scope that holds it
     */
    void collectMemberContexts(Class<?> scopeContext, String declaringScope,
                               InlineContextSink sink) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (DeclaredMember<T, C> member : members) {
            ActionRef<T, C> ref = member.ref();
            if (ref instanceof ActionRef.ById) {
                continue;
            }

            Class<?> declared = ref.declaredContext();
            sink.accept(ref.id(), declared != null ? declared : effectiveScope, declaringScope);

            boolean mapped = !(ref.mapperRef() instanceof MapperRef.PassThrough);
            if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().collectMemberContexts(
                    conditional.def().subtreeContext(effectiveScope, mapped), sink);
            } else if (ref instanceof ActionRef.InlineOperation<T, C> nested) {
                nested.def().collectMemberContexts(
                    nested.def().subtreeContext(effectiveScope, mapped), sink);
            }
        }
    }

    /**
     * Build-time check over every member: that a by-id reference's context crossing is legal,
     * that a nested conditional's branches are checked too, and that a forked member is not
     * silently sharing a context it cannot copy.
     *
     * @param scopeContext the enclosing context type; {@code null} is read as {@code Object}
     * @param scopeLabel names this sequence in a rejection message
     * @param contextOwner names the position whose context the members run against, which is not
     *                     always this sequence: a declaration that names no context of its own
     *                     inherits the enclosing one, and so does every branch of a conditional
     * @param visibleScopes the ids of the scopes a reference from here resolves through, innermost
     *                      first
     * @param smDef the state-machine def whose component registrations the check consults
     */
    void checkRefs(Class<?> scopeContext, String scopeLabel, String contextOwner,
                   List<String> visibleScopes, StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (DeclaredMember<T, C> member : members) {
            ActionRef<T, C> ref = member.ref();
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id(),
                                                                             visibleScopes);
                byId.mapperRef().validateAgainst(effectiveScope, scopeLabel, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            } else if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                Class<?> own = memberContext(ref, conditional.def(), effectiveScope, scopeLabel);
                String label = scopeLabel + " > " + conditional.def().defLabel();
                conditional.def().checkRefs(own, label,
                                            ownerBeneath(ref.declaredContext(), own, contextOwner,
                                                         label),
                                            visibleScopes, smDef);
            } else if (ref instanceof ActionRef.InlineOperation<T, C> nested) {
                Class<?> own = memberContext(ref, nested.def(), effectiveScope, scopeLabel);
                String label = scopeLabel + " > " + nested.def().defLabel();
                nested.def().checkRefs(own, label,
                                       ownerBeneath(ref.declaredContext(), own, contextOwner,
                                                    label),
                                       visibleScopes, smDef);
            } else if (ref instanceof ActionRef.InlineDef<T, C> step) {
                // A step owns no members, so the boundary is all there is to check.
                memberContext(ref, step.def(), effectiveScope, scopeLabel);
            }

            if (member.forked()) {
                checkForkBoundary(ref, effectiveScope, contextOwner, scopeLabel);
            }
        }
    }

    /**
     * Resolves the context an inline declaration runs against, rejecting a boundary it cannot
     * cross.
     * <p>
     * The answer comes from {@link ActionDefImpl#subtreeContext}, which every pass asking that
     * question shares; this method adds only the rejection.
     */
    private Class<?> memberContext(ActionRef<T, C> ref, ActionDefImpl<T, ?, ?> def,
                                   Class<?> effectiveScope, String scopeLabel) {
        boolean mapped = !(ref.mapperRef() instanceof MapperRef.PassThrough);
        if (!def.boundaryIsLegal(effectiveScope, mapped)) {
            throw new TransfluxValidationException(
                "Context type mismatch: " + scopeLabel + " (context " + effectiveScope.getName()
                    + ") declares " + def.defLabel() + " with context "
                    + def.declaredContext().getName() + ", which it is not assignable to."
                    + " An inline declaration without a mapper runs pass-through, so its context"
                    + " must accept the enclosing one.");
        }
        return def.subtreeContext(effectiveScope, mapped);
    }

    private D reference(String verb, String id, boolean forked) {
        owner.requireConfigurerActive(verb);
        members.add(new DeclaredMember<>(ActionRef.byId(id), forked));
        return self;
    }

    private D reference(String verb, String id, String mapperId, boolean forked) {
        owner.requireConfigurerActive(verb);
        requireNotBlank(id, "Action reference ID");
        requireNotBlank(mapperId, "Mapper reference ID");
        members.add(new DeclaredMember<>(ActionRef.byId(id, MapperRef.byId(mapperId)), forked));
        return self;
    }

    private D reference(String verb, String id, ContextMapper<C, ?> inlineMapper, boolean forked) {
        owner.requireConfigurerActive(verb);
        requireNotBlank(id, "Action reference ID");
        requireNotNull(inlineMapper, "Inline mapper instance");
        members.add(new DeclaredMember<>(ActionRef.byId(id, MapperRef.inline(inlineMapper)), forked));
        return self;
    }

    /**
     * Reports the position a nested declaration's members should blame for their context: itself
     * when it named the context they actually run against, otherwise whoever the enclosing
     * sequence was already blaming.
     * <p>
     * The test is {@code declared == own} rather than "did it declare anything", because the two
     * differ for a declaration that names {@link Object} and runs pass-through: it named a context,
     * but its members are handed the enclosing one, so the enclosing position is still what a host
     * would have to change. Restating the enclosing type is the opposite case - the declaration is
     * the nearest place the context is written, so it is the one to name.
     */
    private static String ownerBeneath(Class<?> declared, Class<?> own, String contextOwner,
                                       String ownLabel) {
        return declared == own ? ownLabel : contextOwner;
    }

    /**
     * Warns when a forked member would share the context it was declared against. Nothing is
     * rejected: a shared context may be exactly what the author intended, and the framework
     * cannot tell.
     * <p>
     * {@code contextOwner} names the position that declared the context at stake, which is what
     * the advice is actionable against - it is not necessarily where the member was written, since
     * a conditional's branches, and any declaration naming no context, inherit one from further
     * out. {@code declaredIn} names the position the member was written at.
     */
    private void checkForkBoundary(ActionRef<T, C> ref, Class<?> scopeContext,
                                   String contextOwner, String declaredIn) {
        // A mapper produces the branch's own context, so there is nothing left to share.
        if (!(ref.mapperRef() instanceof MapperRef.PassThrough)
                || scopeContext == Void.class
                || ForkableContext.class.isAssignableFrom(scopeContext)) {
            return;
        }

        if (scopeContext == Object.class) {
            Loggers.BUILD_VALIDATION.warn(
                "Forked member may share the enclosing context; no context type is declared where"
                    + " it runs, so forkability cannot be checked - declare one on the position"
                    + " that owns it, implement ForkableContext, or map at the call site,"
                    + " contextOwner={}, declaredIn={}, actionId={}",
                contextOwner, declaredIn, ref.id());
            return;
        }

        Loggers.BUILD_VALIDATION.warn(
            "Forked member shares the enclosing context; implement ForkableContext or map at the"
                + " call site, contextOwner={}, declaredIn={}, actionId={}, contextType={}",
            contextOwner, declaredIn, ref.id(), scopeContext.getName());
    }

    /**
     * A member as declared: the reference itself, and whether the declaring verb was
     * {@code fork}.
     * <p>
     * The flag rides beside the reference rather than inside it because forking is a property of
     * the call site, exactly as the call-site mapper is - the same registered action is forked at
     * one position and run in line at another.
     *
     * @param ref the member reference
     * @param forked whether this position hands the member to the executor
     * @param <T> the entity type the surrounding state machine manages
     * @param <C> the host-supplied context type carried through transition execution
     */
    record DeclaredMember<T, C>(ActionRef<T, C> ref, boolean forked) {
    }
}

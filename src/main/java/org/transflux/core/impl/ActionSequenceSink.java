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
import org.transflux.core.action.Action;
import org.transflux.core.action.ActionKind;
import org.transflux.core.action.ConditionalOperationDef;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.action.ForkableContext;
import org.transflux.core.action.StepDef;

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
 * extends the sealed {@code ActionDefImpl}, which permits only itself and {@code StepDefImpl},
 * while the two branch defs extend {@code ConfigurableDefImpl} directly. That base supplies
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

    D run(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        return run(registeredAction.getId());
    }

    D run(Identifiable registeredAction, Identifiable mapper) {
        requireNotNull(registeredAction, "Action identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return run(registeredAction.getId(), mapper.getId());
    }

    D run(Identifiable registeredAction, String mapperId) {
        requireNotNull(registeredAction, "Action identifiable");
        return run(registeredAction.getId(), mapperId);
    }

    D run(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return run(id, mapper.getId());
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

    D fork(Identifiable registeredAction) {
        requireNotNull(registeredAction, "Action identifiable");
        return fork(registeredAction.getId());
    }

    D fork(Identifiable registeredAction, Identifiable mapper) {
        requireNotNull(registeredAction, "Action identifiable");
        requireNotNull(mapper, "Mapper identifiable");
        return fork(registeredAction.getId(), mapper.getId());
    }

    D fork(Identifiable registeredAction, String mapperId) {
        requireNotNull(registeredAction, "Action identifiable");
        return fork(registeredAction.getId(), mapperId);
    }

    D fork(String id, Identifiable mapper) {
        requireNotNull(mapper, "Mapper identifiable");
        return fork(id, mapper.getId());
    }

    D step(String id, Action<T, C> action) {
        owner.requireConfigurerActive("step");
        members.add(new DeclaredMember<>(ActionRef.inline(id, action, ActionKind.STEP), false));
        return self;
    }

    D step(Identifiable actionIdentifiable, Action<T, C> action) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), action);
    }

    D step(String id, Class<? extends Action<T, C>> actionClass) {
        owner.requireConfigurerActive("step");
        members.add(new DeclaredMember<>(ActionRef.inline(id, actionClass, ActionKind.STEP), false));
        return self;
    }

    D step(Identifiable actionIdentifiable, Class<? extends Action<T, C>> actionClass) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), actionClass);
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

    D step(Identifiable actionIdentifiable, Consumer<StepDef<T, C>> configurer) {
        requireNotNull(actionIdentifiable, "Step identifiable");
        return step(actionIdentifiable.getId(), configurer);
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

    D conditional(Identifiable conditionalIdentifiable, Consumer<ConditionalOperationDef<T, C>> configurer) {
        requireNotNull(conditionalIdentifiable, "Conditional identifiable");
        return conditional(conditionalIdentifiable.getId(), configurer);
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
     * Build-time check over every member: that a by-id reference's context crossing is legal,
     * that a nested conditional's branches are checked too, and that a forked member is not
     * silently sharing a context it cannot copy.
     *
     * @param scopeContext the enclosing context type; {@code null} is read as {@code Object}
     * @param scopeLabel names this sequence in a rejection message
     * @param enclosingOperationId the id of the container whose context the members run against -
     *                             the same one at a branch position, since a branch never re-types
     * @param smDef the state-machine def whose component registrations the check consults
     */
    void checkRefs(Class<?> scopeContext, String scopeLabel, String enclosingOperationId,
                   StateMachineDefImpl<T> smDef) {
        Class<?> effectiveScope = scopeContext != null ? scopeContext : Object.class;

        for (DeclaredMember<T, C> member : members) {
            ActionRef<T, C> ref = member.ref();
            if (ref instanceof ActionRef.ById<T, ?> byId) {
                Class<?> componentCtx = smDef.componentContextTypeOrDefault(byId.id());
                byId.mapperRef().validateAgainst(effectiveScope, scopeLabel, "action",
                    byId.id(), componentCtx, smDef.getMapperRegistrations());
            } else if (ref instanceof ActionRef.Conditional<T, C> conditional) {
                conditional.def().checkRefs(effectiveScope, enclosingOperationId, smDef);
            }

            if (member.forked()) {
                checkForkBoundary(ref, effectiveScope, enclosingOperationId, scopeLabel);
            }
        }
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
     * Warns when a forked member would share the context it was declared against. Nothing is
     * rejected: a shared context may be exactly what the author intended, and the framework
     * cannot tell.
     * <p>
     * {@code operationId} names the container whose context is at stake - the right anchor, since
     * that is whose context the fork does or does not isolate, and whose {@code usingContext} the
     * message advises declaring. {@code declaredIn} names the position the member was written at,
     * which is the same thing at a container position and a branch at a branch position.
     */
    private void checkForkBoundary(ActionRef<T, C> ref, Class<?> scopeContext,
                                   String enclosingOperationId, String declaredIn) {
        // A mapper produces the branch's own context, so there is nothing left to share.
        if (!(ref.mapperRef() instanceof MapperRef.PassThrough)
                || scopeContext == Void.class
                || ForkableContext.class.isAssignableFrom(scopeContext)) {
            return;
        }

        if (scopeContext == Object.class) {
            Loggers.BUILD_VALIDATION.warn(
                "Forked member may share the enclosing context; the operation declares no context"
                    + " type, so forkability cannot be checked - declare usingContext(...),"
                    + " implement ForkableContext, or map at the call site, operationId={},"
                    + " declaredIn={}, actionId={}", enclosingOperationId, declaredIn, ref.id());
            return;
        }

        Loggers.BUILD_VALIDATION.warn(
            "Forked member shares the enclosing context; implement ForkableContext or map at the"
                + " call site, operationId={}, declaredIn={}, actionId={}, contextType={}",
            enclosingOperationId, declaredIn, ref.id(), scopeContext.getName());
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

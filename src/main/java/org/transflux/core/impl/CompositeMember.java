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

import org.transflux.core.action.ContextMapper;

/**
 * A member of an ordered action list, resolved: the bound action, its call-site context mapping,
 * and whether the declaring verb was {@code fork}. Every position that holds such a list — a
 * declarative container and a conditional's branches — dispatches through {@link #dispatch}.
 *
 * @param action the bound action this position invokes
 * @param mapping the resolved call-site mapping; pass-through when the position declared no mapper
 * @param forked whether this position hands the member to the executor instead of waiting for it
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record CompositeMember<T, C>(BoundAction<T, C> action, ResolvedContextMapping mapping, boolean forked) {

    /**
     * Invokes this member against the supplied execution view.
     *
     * <p>Every member goes through {@link ExecutingTransitionImpl#runAction}, whatever form it was
     * authored in, so compensation capture, id recording and nesting are identical here and at
     * every other dispatch site. Pass-through mode runs the member against the parent context
     * verbatim; mapped mode produces a child context via {@code mapTo}, runs against it, then
     * folds back through {@code mapFrom} on success.
     *
     * <p>Mapper failure attribution: a {@code mapTo} failure throws before the member starts
     * and therefore surfaces as a parent member failure at the member's position — no child
     * step ids are recorded for it, and no compensation is captured for it either. A
     * {@code mapFrom} failure throws after the member has returned successfully, so any inner
     * step ids the member drove are already on the executed list; the failure attaches to the
     * parent's position and is treated as a parent failure. The child's own completion stands,
     * but its compensations still run: a compensation is captured before the action executes and
     * the enclosing transition drains the whole stack on any failure, whatever completed.
     *
     * @param view the execution view this member runs against
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dispatch(ExecutingTransitionImpl<T, C> view) {
        if (forked) {
            // Returns as soon as the branch is handed over, so the members after it start
            // without waiting - the position in this list is when the work begins, not when
            // it ends.
            view.submitBranch((BoundAction) action, mapping);
            return;
        }

        ContextMapper<Object, Object> mapper = mapping.isPassThrough() ? null : mapping.mapper();
        view.runAction((BoundAction) action, mapper);
    }
}

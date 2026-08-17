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

import org.slf4j.Logger;
import org.transflux.core.condition.Condition;
import org.transflux.core.transition.Transition;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Runtime binder that pairs a pure {@link Condition} with framework-owned identity.
 *
 * @param id the framework-owned condition id; never {@code null} or blank
 * @param condition the bound {@link Condition} executable; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCondition<T, C>(String id, Condition<T, C> condition) {

    /**
     * Where a condition sits in an execution. Carried into the log line so a host reading a mixed
     * stream can tell a transition's pre- or post-condition from a branch selector or a trigger's
     * gate. {@code TRIGGER_GATE} is a data trigger's today; event filters do not pass through here,
     * since they run as an {@code EventFilter} rather than a bound condition.
     */
    enum Role {
        PRE_CONDITION,
        POST_CONDITION,
        BRANCH,
        TRIGGER_GATE
    }

    BoundCondition {
        requireNotBlank(id, "Bound condition ID");
        requireNotNull(condition, "Bound condition");
    }

    /**
     * Evaluates the bound condition and reports the outcome.
     *
     * <p>This is the single seam every condition kind passes through, which is why the reporting
     * lives here rather than at the five call sites: the five would drift in format, and a condition
     * position added later would silently emit nothing. It has to be a method rather than a
     * decorator applied at construction, because {@link #fromExpression} builds the record directly
     * and would slip past anything wrapped around {@link #of}.
     *
     * <p>A branch selector reports at TRACE and the rest at DEBUG, matching how many of each an
     * execution produces. A data trigger's gate reports on the trigger logger rather than the
     * condition one, so a host raising the trigger tree to diagnose a dispatch that fired nothing
     * sees the gate's value next to the {@code gate-rejected} line that summarises it.
     *
     * <p>A throw is reported too, and by class name only. It is the one outcome nothing else
     * records: a pre-condition that throws lands in the transition's catch before the start hook
     * fired, so no listener is notified either, and an unreported throw would be indistinguishable
     * in the log from a condition that was never reached.
     *
     * @param role where this condition sits in the execution
     * @param entity the entity under transition
     * @param ctx the context the condition evaluates against; may be {@code null}
     * @param transition the read-only view of the transition being evaluated
     *
     * @return whether the condition holds
     */
    boolean evaluate(Role role, T entity, C ctx, Transition transition) {
        Logger log = role == Role.TRIGGER_GATE ? Loggers.TRIGGER : Loggers.EXECUTION_CONDITION;

        boolean held;
        try {
            held = condition.test(entity, ctx, transition);
        } catch (Exception e) {
            // Aborts the execution whatever the role, so it reports at DEBUG even for a branch.
            if (log.isDebugEnabled()) {
                log.debug("Condition failed, conditionId={}, role={}, errorType={}",
                          id, role, e.getClass().getName());
            }
            throw e;
        }

        if (role == Role.BRANCH) {
            if (log.isTraceEnabled()) {
                log.trace("Condition evaluated, conditionId={}, role={}, held={}", id, role, held);
            }
        } else if (log.isDebugEnabled()) {
            log.debug("Condition evaluated, conditionId={}, role={}, held={}", id, role, held);
        }

        return held;
    }

    /**
     * Convenience factory equivalent to the canonical constructor.
     *
     * @param id the condition id
     * @param condition the condition executable
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a fresh bound condition
     */
    static <T, C> BoundCondition<T, C> of(String id, Condition<T, C> condition) {
        return new BoundCondition<>(id, condition);
    }

    /**
     * Creates a bound condition whose evaluation parses the given SpEL expression and
     * interprets its boolean result.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a bound condition that evaluates {@code expression} on each call
     */
    static <T, C> BoundCondition<T, C> fromExpression(String id, String expression) {
        requireNotBlank(expression, "Expression");
        String expr = expression;
        Condition<T, C> condition = (entity, ctx, transition) ->
            SpelConditionEvaluator.shared().evaluate(expr, entity, ctx, transition);
        return new BoundCondition<>(id, condition);
    }
}

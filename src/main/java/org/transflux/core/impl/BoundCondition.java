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

import org.transflux.core.condition.*;

import static org.transflux.core.impl.ValidationUtils.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;

/**
 * Runtime binder that pairs a pure {@link Condition} with framework-owned identity.
 *
 * <p>This is framework-internal infrastructure; user code should not construct or inspect
 * bound conditions directly.
 *
 * @param id the framework-owned condition id; never {@code null} or blank
 * @param condition the bound {@link Condition} executable; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCondition<T, C>(String id, Condition<T, C> condition) {

    public BoundCondition {
        requireNotBlank(id, "Bound condition ID");
        requireNotNull(condition, "Bound condition");
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
    public static <T, C> BoundCondition<T, C> of(String id, Condition<T, C> condition) {
        return new BoundCondition<>(id, condition);
    }

    /**
     * Creates a bound condition whose evaluation parses the given SpEL expression and
     * interprets its boolean result.
     *
     * <p>This is framework-internal infrastructure; user code should not invoke it directly.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a bound condition that evaluates {@code expression} on each call
     */
    public static <T, C> BoundCondition<T, C> fromExpression(String id, String expression) {
        requireNotBlank(expression, "Expression");
        String expr = expression;
        Condition<T, C> condition = (entity, ctx, transition) ->
            SpelConditionEvaluator.shared().evaluate(expr, entity, ctx, transition);
        return new BoundCondition<>(id, condition);
    }
}

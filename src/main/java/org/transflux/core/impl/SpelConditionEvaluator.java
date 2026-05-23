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

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.transflux.core.impl.ThrowingUtils;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.transflux.core.impl.ValidationUtils.requireNotBlank;

/**
 * Thread-safe SpEL evaluator that compiles each unique expression string at most once and
 * caches the parsed {@link Expression}.
 * <p>
 * Evaluation binds the entity as the SpEL root object and exposes the context and the
 * per-execution {@link Transition} view as the SpEL variables {@code #context} and
 * {@code #transition} respectively.
 * <p>
 * A process-wide singleton is available via {@link #shared()}; framework-owned
 * expression-based conditions use the singleton so cache benefits accumulate across state
 * machines.
 */
final class SpelConditionEvaluator {

    private static final SpelConditionEvaluator SHARED = new SpelConditionEvaluator();

    private final ExpressionParser parser = new SpelExpressionParser();
    private final ConcurrentMap<String, Expression> cache = new ConcurrentHashMap<>();

    SpelConditionEvaluator() {
    }

    /**
     * Returns the process-wide shared evaluator.
     *
     * @return the shared evaluator
     */
    static SpelConditionEvaluator shared() {
        return SHARED;
    }

    /**
     * Parses (or retrieves from cache) the given expression and evaluates it against the
     * supplied scope.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     * @param entity the entity bound as the SpEL root object; may be {@code null}
     * @param context the host-supplied context bound as {@code #context}; may be {@code null}
     * @param transition the per-execution transition view bound as {@code #transition}; may
     *                   be {@code null}
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return the boolean result of evaluating the expression
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank,
     *         if the expression cannot be parsed, if evaluation fails, or if the expression
     *         does not evaluate to a {@code Boolean}
     */
    <T, C> boolean evaluate(String expression, T entity, C context, Transition<T, C> transition) {
        requireNotBlank(expression, "Expression");

        Expression parsed = cache.get(expression);
        if (parsed == null) {
            Expression freshlyParsed = ThrowingUtils.sneakyGet(() -> parser.parseExpression(expression),
                                                               "Invalid SpEL expression '" + expression + "'");
            cache.putIfAbsent(expression, freshlyParsed);
            parsed = cache.get(expression);
        }

        StandardEvaluationContext evalContext = new StandardEvaluationContext(entity);
        evalContext.setVariable("context", context);
        evalContext.setVariable("transition", transition);

        Expression toEvaluate = parsed;
        Object result = ThrowingUtils.sneakyGet(() -> toEvaluate.getValue(evalContext),
                                                "Failed to evaluate SpEL expression '" + expression + "'");

        if (result instanceof Boolean b) {
            return b;
        }

        String resultType = result == null ? "null" : result.getClass().getName();
        throw new TransfluxValidationException(
            "SpEL expression '" + expression + "' must evaluate to boolean but returned " + resultType);
    }

    int cacheSize() {
        return cache.size();
    }
}

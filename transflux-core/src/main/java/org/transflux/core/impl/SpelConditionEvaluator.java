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


import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.transition.Transition;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Parses and evaluates the SpEL a definition writes. It keeps nothing: whoever binds an
 * expression holds its parsed form, so it lives exactly as long as the definition that declared it.
 * <p>
 * Evaluation binds the entity as the SpEL root object and exposes the context and the
 * per-execution {@link Transition} view as the SpEL variables {@code #context} and
 * {@code #transition} respectively. The entity is bound a second time as {@code #entity}, which
 * is how an expression passes it to a method whole - the root has no other spelling.
 */
final class SpelConditionEvaluator {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private SpelConditionEvaluator() {
    }

    /**
     * Evaluates a parsed condition expression against the supplied scope.
     *
     * @param expression the parsed expression
     * @param entity the entity bound as the SpEL root object and as {@code #entity}; may be {@code null}
     * @param context the host-supplied context bound as {@code #context}; may be {@code null}
     * @param transition the read-only transition view bound as {@code #transition}; may be {@code null}
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return the boolean result of evaluating the expression
     *
     * @throws TransfluxValidationException if evaluation fails or does not yield a {@code Boolean}
     */
    static <T, C> boolean evaluate(Expression expression, T entity, C context, Transition transition) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext(entity);
        evalContext.setVariable("entity", entity);
        evalContext.setVariable("context", context);
        evalContext.setVariable("transition", transition);

        return evaluateBoolean(expression, evalContext);
    }

    /**
     * Evaluates a parsed event-filter expression, which binds the entity as the root and as
     * {@code #entity}, the event payload as {@code #event}, and the context as {@code #context}.
     *
     * @param expression the parsed expression
     * @param entity the entity bound as the SpEL root object and as {@code #entity}; may be {@code null}
     * @param eventData the event payload bound as {@code #event}; may be {@code null}
     * @param context the host-supplied context bound as {@code #context}; may be {@code null}
     * @param <T> the entity type
     *
     * @return the boolean result of evaluating the expression
     *
     * @throws TransfluxValidationException if evaluation fails or does not yield a {@code Boolean}
     */
    static <T> boolean evaluateEventFilter(Expression expression, T entity, Object eventData, Object context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext(entity);
        evalContext.setVariable("entity", entity);
        evalContext.setVariable("event", eventData);
        evalContext.setVariable("context", context);

        return evaluateBoolean(expression, evalContext);
    }

    /**
     * Parses an expression and discards it, so a malformed one is refused where it is declared
     * rather than at its first evaluation.
     *
     * @param expression the SpEL expression text
     *
     * @throws TransfluxValidationException if the expression is blank or cannot be parsed
     */
    static void validate(String expression) {
        parse(expression);
    }

    /**
     * Parses one expression, keeping the parser's own complaint in the message.
     * <p>
     * This is the one place the framework repeats a failure's message rather than its type, and it
     * is safe for the reason the redaction exists: parsing reads the expression the definition
     * author wrote - already quoted here - and no entity or context, so the parser has no host data
     * to leak. It is also the only diagnostic there is, since "the expression is invalid" without
     * saying where is not something an author can act on.
     *
     * @param expression the SpEL expression text
     *
     * @return the parsed expression
     *
     * @throws TransfluxValidationException if the expression is blank or cannot be parsed
     */
    static Expression parse(String expression) {
        requireNotBlank(expression, "Expression");
        try {
            return PARSER.parseExpression(expression);
        } catch (RuntimeException e) {
            throw new TransfluxValidationException(
                "Invalid SpEL expression '" + expression + "': " + e.getMessage(), e);
        }
    }

    private static boolean evaluateBoolean(Expression expression, StandardEvaluationContext evalContext) {
        Object result;
        try {
            result = expression.getValue(evalContext);
        } catch (RuntimeException e) {
            // Built here rather than up front: this runs per condition, and success is the common case.
            throw new TransfluxValidationException("Failed to evaluate SpEL expression '"
                + expression.getExpressionString() + "': " + e.getClass().getName(), e);
        }

        if (result instanceof Boolean b) {
            return b;
        }

        String resultType = result == null ? "null" : result.getClass().getName();
        throw new TransfluxValidationException("SpEL expression '" + expression.getExpressionString()
            + "' must evaluate to boolean but returned " + resultType);
    }
}

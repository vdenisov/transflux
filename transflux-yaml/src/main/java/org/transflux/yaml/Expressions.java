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

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.transflux.core.action.ContextMapper;
import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.state.StateApplier;
import org.transflux.core.state.StateResolver;
import org.yaml.snakeyaml.nodes.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The expressions a document writes where the Java DSL takes a lambda: the state resolver and
 * applier, a mapper's {@code mapTo} and {@code mapFrom}, and a route's guard. Each is parsed when
 * it is read, so a malformed one is refused at its line; conditions and event filters are not here,
 * since the definition they are handed to parses them itself.
 */
final class Expressions {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private Expressions() {
    }

    /**
     * Reads a mapper's {@code mapTo} and optional {@code mapFrom} into a context mapper.
     *
     * @param map the mapping holding {@code mapTo}
     *
     * @return a mapper evaluating {@code mapTo} against the parent context, and assigning each
     *         {@code mapFrom} entry into the parent in document order
     *
     * @throws DefinitionLoadException when an expression or a property path does not parse
     */
    static ContextMapper<Object, Object> mapper(NodeMap map) {
        String mapTo = map.requiredString("mapTo");
        Expression parsedMapTo = parse(map, map.requiredNode("mapTo"), mapTo);

        List<Assignment> mapFrom = new ArrayList<>();
        NodeMap entries = map.optionalMap("mapFrom");
        if (entries != null) {
            for (String target : entries.keys()) {
                String value = entries.requiredString(target);
                mapFrom.add(new Assignment(parse(entries, entries.keyNode(target), target), target,
                    parse(entries, entries.requiredNode(target), value), value));
            }
        }
        return new SpelContextMapper(parsedMapTo, mapTo, List.copyOf(mapFrom));
    }

    /**
     * Reads a state resolver expression, evaluated against the entity.
     *
     * @param map the mapping holding {@code expression}
     *
     * @return a resolver returning the expression's value as a state id: an enum contributes its
     *         {@code name()}, anything else its {@code toString()}, and {@code null} stays null
     *
     * @throws DefinitionLoadException when the expression does not parse
     */
    static StateResolver<Object> resolver(NodeMap map) {
        String text = map.requiredString("expression");
        Expression expression = parse(map, map.requiredNode("expression"), text);
        return entity -> {
            Object state = evaluate(expression, text, entityContext(entity));
            if (state instanceof Enum<?> constant) {
                return constant.name();
            }
            return state == null ? null : state.toString();
        };
    }

    /**
     * Reads a state applier expression, which is an assignment target on the entity.
     *
     * @param map the mapping holding {@code expression}
     *
     * @return an applier assigning the new state id through the expression; SpEL's standard
     *         conversion turns the id into an enum-typed property
     *
     * @throws DefinitionLoadException when the expression does not parse
     */
    static StateApplier<Object> applier(NodeMap map) {
        String text = map.requiredString("expression");
        Expression expression = parse(map, map.requiredNode("expression"), text);
        return (entity, newStateId) -> {
            try {
                expression.setValue(new StandardEvaluationContext(entity), newStateId);
            } catch (RuntimeException e) {
                // The type alone: a failure's message may carry the host values it was evaluated against.
                throw new TransfluxValidationException("Failed to assign state applier target '" + text + "': "
                    + e.getClass().getName(), e);
            }
        };
    }

    /**
     * Reads a route's guard expression, evaluated with the failure as root.
     *
     * @param map the mapping holding {@code expression}
     *
     * @return the guard
     *
     * @throws DefinitionLoadException when the expression does not parse
     */
    static Predicate<Object> guard(NodeMap map) {
        String text = map.requiredString("expression");
        Expression expression = parse(map, map.requiredNode("expression"), text);
        return failure -> {
            Object result = evaluate(expression, text, new StandardEvaluationContext(failure));
            if (result instanceof Boolean matched) {
                return matched;
            }
            throw new TransfluxValidationException("Guard expression '" + text
                + "' must evaluate to boolean but returned " + (result == null ? "null" : result.getClass().getName()));
        };
    }

    /**
     * @param entity the entity
     *
     * @return an evaluation context with the entity as root and bound to {@code #entity}
     */
    private static StandardEvaluationContext entityContext(Object entity) {
        StandardEvaluationContext context = new StandardEvaluationContext(entity);
        context.setVariable("entity", entity);
        return context;
    }

    private static Expression parse(NodeMap map, Node at, String text) {
        try {
            return PARSER.parseExpression(text);
        } catch (RuntimeException e) {
            // The parser's complaint quotes the expression the author wrote, never host data.
            throw map.error(at, "invalid SpEL expression '" + text + "': " + e.getMessage(), e);
        }
    }

    private static Object evaluate(Expression expression, String text, StandardEvaluationContext context) {
        try {
            return expression.getValue(context);
        } catch (RuntimeException e) {
            // The type alone: a failure's message may carry the host values it was evaluated against.
            throw new TransfluxValidationException("Failed to evaluate SpEL expression '" + text + "': "
                + e.getClass().getName(), e);
        }
    }

    private record Assignment(Expression target, String targetText, Expression value, String valueText) {
    }

    private record SpelContextMapper(Expression mapTo, String mapToText, List<Assignment> mapFrom)
        implements ContextMapper<Object, Object> {

        @Override
        public Object mapTo(Object parentContext) {
            return evaluate(mapTo, mapToText, new StandardEvaluationContext(parentContext));
        }

        @Override
        public void mapFrom(Object parentContext, Object nestedContext) {
            if (mapFrom.isEmpty()) {
                return;
            }
            StandardEvaluationContext child = new StandardEvaluationContext(nestedContext);
            child.setVariable("parent", parentContext);
            StandardEvaluationContext parent = new StandardEvaluationContext(parentContext);
            for (Assignment assignment : mapFrom) {
                Object value = evaluate(assignment.value(), assignment.valueText(), child);
                try {
                    assignment.target().setValue(parent, value);
                } catch (RuntimeException e) {
                    throw new TransfluxValidationException("Failed to assign mapFrom target '"
                        + assignment.targetText() + "': " + e.getClass().getName(), e);
                }
            }
        }
    }
}

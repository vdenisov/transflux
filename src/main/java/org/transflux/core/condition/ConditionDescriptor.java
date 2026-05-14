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

package org.transflux.core.condition;

import org.transflux.core.exception.TransfluxValidationException;

import java.util.function.Predicate;

import static org.transflux.core.ValidationUtils.requireNotBlank;
import static org.transflux.core.ValidationUtils.requireNotNull;

/**
 * Discriminated representation of the four authoring forms a transition condition can take:
 * a reference to a previously registered condition, a class to be reflectively instantiated,
 * an arbitrary {@link Predicate} adapted to a {@link Condition}, or a SpEL expression string.
 * <p>
 * Reference, class, and predicate forms require an explicit non-blank id. The expression
 * form allows an optional id; when omitted, the id is auto-derived from the expression text
 * and the descriptor's position within the enclosing state machine (see
 * {@link ExpressionIdDerivation}).
 */
public sealed interface ConditionDescriptor
    permits ConditionDescriptor.Reference,
            ConditionDescriptor.ClassBased,
            ConditionDescriptor.PredicateBased,
            ConditionDescriptor.ExpressionBased {

    /**
     * Returns the explicit id supplied at authoring time, or {@code null} for an expression
     * descriptor whose id should be auto-derived during resolution.
     *
     * @return the explicit id, or {@code null}
     */
    String id();

    /**
     * Creates a descriptor that references a previously registered condition.
     *
     * @param id the registered condition id; never {@code null} or blank
     *
     * @return a reference descriptor
     *
     * @throws TransfluxValidationException if {@code id} is {@code null} or blank
     */
    static ConditionDescriptor ref(String id) {
        return new Reference(id);
    }

    /**
     * Creates a descriptor that binds a {@link Condition} class under the given id; the class
     * is reflectively instantiated through its public no-arg constructor during resolution.
     *
     * @param id the condition id; never {@code null} or blank
     * @param conditionClass the condition class; never {@code null}
     *
     * @return a class-based descriptor
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code conditionClass} is {@code null}
     */
    static ConditionDescriptor classBased(String id, Class<? extends Condition<?, ?>> conditionClass) {
        return new ClassBased(id, conditionClass);
    }

    /**
     * Creates a descriptor that adapts a {@link Predicate} over the entity into a
     * {@link Condition} under the given id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param predicate the predicate; never {@code null}
     *
     * @return a predicate-based descriptor
     *
     * @throws TransfluxValidationException if {@code id} is {@code null}/blank or
     *         {@code predicate} is {@code null}
     */
    static ConditionDescriptor predicate(String id, Predicate<?> predicate) {
        return new PredicateBased(id, predicate);
    }

    /**
     * Creates an expression-based descriptor with an auto-derived id. The id is computed
     * deterministically from the expression text and the descriptor's path within the
     * enclosing state machine during resolution.
     *
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return an expression-based descriptor with no explicit id
     *
     * @throws TransfluxValidationException if {@code expression} is {@code null} or blank
     */
    static ConditionDescriptor expression(String expression) {
        return new ExpressionBased(null, expression);
    }

    /**
     * Creates an expression-based descriptor with an explicit id.
     *
     * @param id the condition id; never {@code null} or blank
     * @param expression the SpEL expression text; never {@code null} or blank
     *
     * @return an expression-based descriptor
     *
     * @throws TransfluxValidationException if {@code id} or {@code expression} is
     *         {@code null} or blank
     */
    static ConditionDescriptor expression(String id, String expression) {
        requireNotBlank(id, "Condition ID");
        return new ExpressionBased(id, expression);
    }

    record Reference(String id) implements ConditionDescriptor {
        public Reference {
            requireNotBlank(id, "Condition reference ID");
        }
    }

    record ClassBased(String id, Class<? extends Condition<?, ?>> conditionClass) implements ConditionDescriptor {
        public ClassBased {
            requireNotBlank(id, "Condition ID");
            requireNotNull(conditionClass, "Condition class");
        }
    }

    record PredicateBased(String id, Predicate<?> predicate) implements ConditionDescriptor {
        public PredicateBased {
            requireNotBlank(id, "Condition ID");
            requireNotNull(predicate, "Predicate");
        }
    }

    record ExpressionBased(String id, String expression) implements ConditionDescriptor {
        public ExpressionBased {
            requireNotBlank(expression, "Expression");
        }
    }
}

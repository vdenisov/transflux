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

import org.transflux.core.condition.Condition;
import org.transflux.core.condition.ConditionDescriptor;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.Map;
import java.util.function.BiPredicate;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Stateless resolver that turns a {@link ConditionDescriptor} into a {@link BoundCondition}
 * suitable for execution.
 * <p>
 * Reference descriptors are looked up against the state machine's condition registry;
 * instance-based descriptors return the wrapped {@code Condition} as-is;
 * predicate-based descriptors are adapted into {@code Condition} instances that pass the
 * entity and context through to the underlying predicate (ignoring the transition view);
 * expression-based descriptors are parsed and bound holding what was parsed, with id
 * auto-derived from the supplied path when the descriptor omits an explicit id.
 */
final class ConditionResolver {

    private ConditionResolver() {
        // utility class — no instances
    }

    /**
     * Resolves the descriptor against the registry.
     *
     * @param descriptor the descriptor to resolve; never {@code null}
     * @param registry the state machine's condition registry, keyed by id
     * @param path slash-separated location of the descriptor within the enclosing state
     *             machine, used for auto-id derivation on expression-based descriptors with
     *             no explicit id
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return the bound condition
     *
     * @throws TransfluxValidationException if an input is invalid
     * @throws IllegalStateException if a reference points at an unregistered id, which the build
     *         refuses before resolving
     */
    static <T, C> BoundCondition<T, C> resolve(ConditionDescriptor descriptor,
                                                      Map<String, BoundCondition<T, C>> registry,
                                                      String path, SpelConditionEvaluator evaluator) {
        requireNotNull(descriptor, "Condition descriptor");
        requireNotNull(registry, "Condition registry");
        requireNotNull(path, "Path");

        if (descriptor instanceof ConditionDescriptor.Reference ref) {
            return resolveReference(ref, registry);
        }

        if (descriptor instanceof ConditionDescriptor.InstanceBased ib) {
            return resolveInstanceBased(ib);
        }

        if (descriptor instanceof ConditionDescriptor.PredicateBased pb) {
            return resolvePredicateBased(pb);
        }

        if (descriptor instanceof ConditionDescriptor.ExpressionBased eb) {
            return resolveExpressionBased(eb, path, evaluator);
        }

        throw new TransfluxValidationException(
            "Unsupported condition descriptor: " + descriptor.getClass().getName());
    }

    private static <T, C> BoundCondition<T, C> resolveReference(ConditionDescriptor.Reference descriptor,
                                                                Map<String, BoundCondition<T, C>> registry) {
        BoundCondition<T, C> bound = registry.get(descriptor.id());

        if (bound == null) {
            // The build checks every reference position first and names it; reaching here means one was missed.
            throw new IllegalStateException("Condition reference '" + descriptor.id()
                + "' was not checked at build: no condition is registered under it");
        }

        return bound;
    }

    @SuppressWarnings("unchecked")
    private static <T, C> BoundCondition<T, C> resolveInstanceBased(ConditionDescriptor.InstanceBased descriptor) {
        Condition<? super T, C> instance = (Condition<? super T, C>) descriptor.condition();
        return BoundCondition.of(descriptor.id(), instance);
    }

    @SuppressWarnings("unchecked")
    private static <T, C> BoundCondition<T, C> resolvePredicateBased(ConditionDescriptor.PredicateBased descriptor) {
        BiPredicate<? super T, C> predicate = (BiPredicate<? super T, C>) descriptor.predicate();
        Condition<T, C> adapted = (entity, ctx, transition) -> predicate.test(entity, ctx);
        return BoundCondition.of(descriptor.id(), adapted);
    }

    private static <T, C> BoundCondition<T, C> resolveExpressionBased(ConditionDescriptor.ExpressionBased descriptor,
                                                                      String path, SpelConditionEvaluator evaluator) {
        String id = descriptor.id();

        if (id == null) {
            id = ExpressionIdDerivation.deriveId(descriptor.expression(), path);
        }

        return BoundCondition.fromExpression(id, descriptor.expression(), evaluator);
    }
}

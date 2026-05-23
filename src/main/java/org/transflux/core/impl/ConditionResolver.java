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

import org.transflux.core.exception.TransfluxValidationException;

import java.util.Map;
import java.util.function.Predicate;

import static org.transflux.core.impl.ReflectionUtils.instantiateNoArg;
import static org.transflux.core.impl.ValidationUtils.requireNotNull;

/**
 * Stateless resolver that turns a {@link ConditionDescriptor} into a {@link BoundCondition}
 * suitable for execution.
 * <p>
 * Reference descriptors are looked up against the state machine's condition registry;
 * class-based descriptors are reflectively instantiated through their public no-arg
 * constructor; instance-based descriptors return the wrapped {@code Condition} as-is;
 * predicate-based descriptors are adapted into {@code Condition} instances that ignore the
 * context and transition view; expression-based descriptors are bound to the shared
 * {@link SpelConditionEvaluator}, with id auto-derived from the supplied path when the
 * descriptor omits an explicit id.
 *
 * <p>This is framework-internal infrastructure used by Transflux's own def builders; user
 * code should not invoke it directly.
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
     * @throws TransfluxValidationException if a reference points at an unregistered id, if
     *         a class-based descriptor's class cannot be instantiated through a no-arg
     *         constructor, or if any other input is invalid
     */
    public static <T, C> BoundCondition<T, C> resolve(ConditionDescriptor descriptor,
                                                      Map<String, BoundCondition<T, C>> registry,
                                                      String path) {
        requireNotNull(descriptor, "Condition descriptor");
        requireNotNull(registry, "Condition registry");
        requireNotNull(path, "Path");

        if (descriptor instanceof ConditionDescriptor.Reference ref) {
            return resolveReference(ref, registry);
        }

        if (descriptor instanceof ConditionDescriptor.ClassBased cb) {
            return resolveClassBased(cb);
        }

        if (descriptor instanceof ConditionDescriptor.InstanceBased ib) {
            return resolveInstanceBased(ib);
        }

        if (descriptor instanceof ConditionDescriptor.PredicateBased pb) {
            return resolvePredicateBased(pb);
        }

        if (descriptor instanceof ConditionDescriptor.ExpressionBased eb) {
            return resolveExpressionBased(eb, path);
        }

        throw new TransfluxValidationException(
            "Unsupported condition descriptor: " + descriptor.getClass().getName());
    }

    private static <T, C> BoundCondition<T, C> resolveReference(ConditionDescriptor.Reference descriptor,
                                                                Map<String, BoundCondition<T, C>> registry) {
        BoundCondition<T, C> bound = registry.get(descriptor.id());

        if (bound == null) {
            throw new TransfluxValidationException(
                "No condition registered with id '" + descriptor.id() + "'");
        }

        return bound;
    }

    @SuppressWarnings("unchecked")
    private static <T, C> BoundCondition<T, C> resolveClassBased(ConditionDescriptor.ClassBased descriptor) {
        Class<? extends Condition<?, ?>> conditionClass = descriptor.conditionClass();
        Condition<T, C> instance = (Condition<T, C>) instantiateNoArg(conditionClass, "Condition");
        return BoundCondition.of(descriptor.id(), instance);
    }

    @SuppressWarnings("unchecked")
    private static <T, C> BoundCondition<T, C> resolveInstanceBased(ConditionDescriptor.InstanceBased descriptor) {
        Condition<T, C> instance = (Condition<T, C>) descriptor.condition();
        return BoundCondition.of(descriptor.id(), instance);
    }

    @SuppressWarnings("unchecked")
    private static <T, C> BoundCondition<T, C> resolvePredicateBased(ConditionDescriptor.PredicateBased descriptor) {
        Predicate<T> predicate = (Predicate<T>) descriptor.predicate();
        Condition<T, C> adapted = (entity, ctx, transition) -> predicate.test(entity);
        return BoundCondition.of(descriptor.id(), adapted);
    }

    private static <T, C> BoundCondition<T, C> resolveExpressionBased(ConditionDescriptor.ExpressionBased descriptor,
                                                                      String path) {
        String id = descriptor.id();

        if (id == null) {
            id = ExpressionIdDerivation.deriveId(descriptor.expression(), path);
        }

        String expression = descriptor.expression();
        Condition<T, C> condition = (entity, ctx, transition) ->
            SpelConditionEvaluator.shared().evaluate(expression, entity, ctx, transition);

        return BoundCondition.of(id, condition);
    }
}

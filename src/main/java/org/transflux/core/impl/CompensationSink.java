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

import org.transflux.core.action.Compensation;
import org.transflux.core.action.CompensationRouteDef;
import org.transflux.core.exception.TransfluxValidationException;

import java.util.ArrayList;
import java.util.List;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Shared implementation and storage for the compensation surface every action def exposes: the
 * unconditional {@code withCompensation} fallback and the ordered {@code forException} routes.
 * <p>
 * Every owning def declares one sink and implements its public methods as one-line delegates, so
 * validation order, argument labels, the configurer guard and the build-time checks are written
 * once. {@code StepDefImpl} and {@code OperationDefImpl} share one sink declared on their common
 * base, {@code ActionDefImpl}. {@code ConditionalOperationDefImpl} declares a second one of its own:
 * it deliberately extends {@link IdentifiedDefImpl} instead of that sealed base, so there is no
 * single place to hang the storage for all three. This mirrors {@link ActionListenerSink} exactly,
 * and for the same reason.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the context type the owning action runs against
 * @param <D> the def interface returned by every overload, for fluent chaining
 */
final class CompensationSink<T, C, D> {

    private final ConfigurableDefImpl owner;
    private final D self;

    private final InstanceOrClassSource<Compensation<T, C>> fallback;
    private final List<CompensationRouteDefImpl<T, C, ?, D>> routes = new ArrayList<>();

    /**
     * Creates a sink for one action def.
     *
     * @param owner the def whose configurer guard gates every registration
     * @param self the value returned by the fallback setters, and by each route when it closes
     */
    CompensationSink(ConfigurableDefImpl owner, D self) {
        this.owner = owner;
        this.self = self;
        this.fallback = new InstanceOrClassSource<>(Loggers.BUILD_VALIDATION, "Compensation source",
                                                    owner.defLabel());
    }

    D withCompensationInstance(Compensation<T, C> compensation) {
        owner.requireConfigurerActive("withCompensation");
        requireNotNull(compensation, "Compensation");
        fallback.setInstance(compensation);
        return self;
    }

    D withCompensationClass(Class<? extends Compensation<T, C>> compensationClass) {
        owner.requireConfigurerActive("withCompensation");
        requireNotNull(compensationClass, "Compensation class");
        fallback.setClass(compensationClass);
        return self;
    }

    <X extends Throwable> CompensationRouteDef<T, C, X, D> forException(Class<X> exceptionType) {
        owner.requireConfigurerActive("forException");
        requireNotNull(exceptionType, "Compensation route exception type");

        CompensationRouteDefImpl<T, C, X, D> route =
            new CompensationRouteDefImpl<>(owner, self, exceptionType);
        routes.add(route);

        return route;
    }

    /**
     * Resolves the declared compensation surface into the table the rollback stack carries.
     *
     * @return the table, or {@code null} when the def declared nothing at all
     *
     * @throws TransfluxValidationException if a route was opened but never closed with a
     *         compensation
     */
    BoundCompensationRouter<T, C> buildRouter() {
        Compensation<T, C> declaredFallback = fallback.resolveOptional("Compensation");

        if (routes.isEmpty()) {
            return declaredFallback == null ? null : BoundCompensationRouter.always(declaredFallback);
        }

        List<BoundCompensationRoute<T, C>> bound = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            CompensationRouteDefImpl<T, C, ?, D> route = routes.get(i);
            if (!route.isClosed()) {
                throw new TransfluxValidationException(
                    route.label() + " declares no compensation; close the route with "
                        + "withCompensation(...) or drop it");
            }
            warnIfShadowed(route, i);
            bound.add(route.buildBound());
        }

        Loggers.BUILD_BINDING.debug("Compensation routes bound, owner={}, routes={}",
                                    owner.defLabel(), bound.size());

        return new BoundCompensationRouter<>(bound, declaredFallback);
    }

    /**
     * Warns when an earlier route provably swallows this one: its type is a supertype and it
     * declared no guard, so nothing this one answers for can ever reach it.
     *
     * <p>A warning rather than an error, because a guarded earlier route is only <em>possibly</em>
     * shadowing - the guard may reject exactly the cases this route wants - and the framework
     * cannot tell. Only the provable case is reported.
     */
    private void warnIfShadowed(CompensationRouteDefImpl<T, C, ?, D> route, int index) {
        for (int i = 0; i < index; i++) {
            CompensationRouteDefImpl<T, C, ?, D> earlier = routes.get(i);
            if (earlier.hasGuard()
                || !earlier.getExceptionType().isAssignableFrom(route.getExceptionType())) {
                continue;
            }
            Loggers.BUILD_VALIDATION.warn(
                "Compensation route is unreachable, owner={}, exceptionType={}, shadowedBy={}",
                owner.defLabel(), route.getExceptionType().getName(),
                earlier.getExceptionType().getName());
            return;
        }
    }
}

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

import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Implementation of {@link CompensationRouteDef}.
 * <p>
 * Deliberately not a {@link ConfigurableDefImpl}: a route has no id, no name, no description and no
 * children, so the only thing it needs from that base is the configurer guard - which it borrows
 * from the def it was opened on rather than carrying a second copy of. That is what makes the
 * continuation shape cheaper than a configurer-based handler def would have been, and it is the
 * same borrowing {@link ActionListenerSink} does.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type the owning action runs against
 * @param <X> the failure type this route answers for
 * @param <D> the def this route was opened on, returned when the route is closed
 */
final class CompensationRouteDefImpl<T, C, X extends Throwable, D>
    implements CompensationRouteDef<T, C, X, D> {

    private final ConfigurableDefImpl owner;
    private final D self;
    private final Class<X> exceptionType;
    private final InstanceSource<Compensation<T, C>> compensation;

    private Predicate<X> guard;

    CompensationRouteDefImpl(ConfigurableDefImpl owner, D self, Class<X> exceptionType) {
        this.owner = owner;
        this.self = self;
        this.exceptionType = exceptionType;
        this.compensation = new InstanceSource<>(Loggers.BUILD_VALIDATION,
                                                        "Compensation source", label());
    }

    @Override
    public CompensationRouteDef<T, C, X, D> matching(Predicate<X> guard) {
        owner.requireConfigurerActive("matching");
        requireNotNull(guard, "Compensation route guard");
        ValidationUtils.warnIfSet(this.guard != null, "Compensation route guard", label(),
                                  Loggers.BUILD_VALIDATION);
        this.guard = guard;
        return this;
    }

    @Override
    public D withCompensation(Compensation<T, C> compensation) {
        owner.requireConfigurerActive("withCompensation");
        requireNotNull(compensation, "Compensation");
        this.compensation.setInstance(compensation);
        return self;
    }

    Class<X> getExceptionType() {
        return exceptionType;
    }

    /**
     * Reports whether this route was closed. A route opened and dropped without a terminal
     * {@code withCompensation} is the one mistake the continuation shape could otherwise hide, so
     * the build reads this rather than letting the route quietly declare nothing.
     *
     * @return whether a compensation was declared
     */
    boolean isClosed() {
        return compensation.isSet();
    }

    /**
     * Reports whether this route narrows its exception type with a guard. Read by the build's
     * shadowing check: an unguarded route provably swallows every narrower one declared after it,
     * while a guarded one may not.
     *
     * @return whether a guard was declared
     */
    boolean hasGuard() {
        return guard != null;
    }

    /**
     * Human-readable phrase naming this route, embedded in build diagnostics.
     *
     * @return the label, e.g. {@code "step 'charge-card' route for 'java.io.IOException'"}
     */
    String label() {
        return owner.defLabel() + " route for '" + exceptionType.getName() + "'";
    }

    /**
     * Resolves this route into its runtime form.
     *
     * <p>The guard widens to {@code Predicate<Throwable>} here because the runtime holds routes of
     * differing failure types in one list. The cast is safe by construction:
     * {@link BoundCompensationRoute#matches} consults the guard only once the type has matched.
     *
     * @return the bound route
     */
    BoundCompensationRoute<T, C> buildBound() {
        Predicate<X> declared = guard;
        Predicate<Throwable> widened =
            declared == null ? null : error -> declared.test(exceptionType.cast(error));

        return new BoundCompensationRoute<>(exceptionType, widened,
                                            compensation.resolve("Compensation"));
    }
}

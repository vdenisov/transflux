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

package org.transflux.core.action;

import java.util.function.Predicate;

/**
 * One entry of an action's compensation routing table, opened by
 * {@link ActionDef#forException(Class)} and closed by one of the {@code withCompensation} methods
 * here, which hands the fluent chain back to the action def it was opened on.
 *
 * <pre>{@code
 * .step("charge-card", s -> s
 *     .using(ChargeCardAction.class)
 *     .withCompensation(RefundCompensation.class)
 *     .forException(GatewayTimeoutException.class)
 *         .withCompensation(ReconcileLaterCompensation.class)
 *     .forException(CardDeclinedException.class)
 *         .matching(e -> e.isPermanent())
 *         .withCompensation(BlacklistCardCompensation.class))
 * }</pre>
 *
 * <p>"Normally refund; on a gateway timeout reconcile later instead; on a permanent decline,
 * blacklist the card."
 *
 * <p>A route must declare a compensation. Opening one and dropping the result without calling
 * {@code withCompensation} fails the build rather than silently declaring nothing.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type the owning action runs against
 * @param <X> the failure type this route answers for
 * @param <D> the action def this route was opened on, returned when the route is closed
 */
public interface CompensationRouteDef<T, C, X extends Throwable, D> {

    /**
     * Narrows this route with an additional test over the failure, for when the exception type
     * alone does not distinguish it - a status code, an error kind, a retryable flag.
     * <p>
     * The guard is consulted only after the type matches, so it is handed the failure already
     * narrowed to {@code X}. A guard that rejects falls through to the next route rather than
     * ending the search, and one that throws is treated as a rejection: this runs inside a rollback
     * that is already unwinding a failure, and a second one escaping would lose the first.
     *
     * <p>Calling this a second time replaces the prior guard and logs a warning.
     *
     * @param guard the test over the failure; never {@code null}
     *
     * @return this route for chaining
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code guard} is
     *         {@code null}, or if the owning def's configurer has already returned
     */
    CompensationRouteDef<T, C, X, D> matching(Predicate<X> guard);

    /**
     * Declares the compensation this route answers with, closing the route.
     * <p>
     * It receives the same entity and context the action ran against, exactly as an unrouted
     * {@link ActionDef#withCompensation(Compensation)} does - the route decides <em>whether</em>
     * this compensation runs, not what it is handed.
     *
     * <p>Calling this a second time replaces the prior declaration and logs a warning.
     *
     * @param compensation the compensation; never {@code null}
     *
     * @return the action def this route was opened on, for chaining
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if {@code compensation} is
     *         {@code null}, or if the owning def's configurer has already returned
     */
    D withCompensation(Compensation<T, C> compensation);

}

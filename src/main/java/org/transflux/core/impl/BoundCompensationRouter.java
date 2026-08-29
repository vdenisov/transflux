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
import org.transflux.core.transition.ActionPath;

import java.util.List;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * An action's resolved compensation table: the routes it declared, in declaration order, plus the
 * unconditional fallback.
 * <p>
 * The router is what the rollback stack carries, rather than a compensation, because the choice
 * cannot be made when the compensation is captured. Capture happens before the action runs; which
 * rollback applies depends on the failure that has not happened yet. So the stack holds the whole
 * table and {@link #select(Throwable, ActionPath)} resolves it once the drain knows the throwable.
 *
 * @param routes the declared routes, tried in order; never {@code null}, possibly empty
 * @param fallback the compensation to use when no route matches: the one the def declared, or -
 *                 where it declared none - whatever the action's own
 *                 {@link org.transflux.core.action.Action#getCompensation(Object, Object)} returned,
 *                 folded in at push time by {@link #withFallback}. {@code null} when neither
 *                 supplied one, in which case a failure outside every route rolls this action back
 *                 not at all and it contributes nothing to the compensated path
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCompensationRouter<T, C>(List<BoundCompensationRoute<T, C>> routes,
                                     Compensation<T, C> fallback) {

    BoundCompensationRouter {
        requireNotNull(routes, "Compensation routes");
        routes = List.copyOf(routes);
    }

    /**
     * Builds the degenerate router for an action whose compensation applies to every failure - a
     * bare {@code withCompensation(...)} declaration, or one returned from
     * {@link org.transflux.core.action.Action#getCompensation(Object, Object)}.
     *
     * @param compensation the unconditional compensation; never {@code null}
     * @param <T> the entity type
     * @param <C> the context type
     *
     * @return a router with no routes and the supplied fallback
     */
    static <T, C> BoundCompensationRouter<T, C> always(Compensation<T, C> compensation) {
        requireNotNull(compensation, "Compensation");
        return new BoundCompensationRouter<>(List.of(), compensation);
    }

    /**
     * Returns this table with {@code dynamic} installed as its fallback, for folding in what
     * {@link org.transflux.core.action.Action#getCompensation(Object, Object)} returned.
     * <p>
     * A table that already declared a fallback keeps it - the def-side declaration is the more
     * specific statement, and it answers every failure anyway - and a {@code null} argument changes
     * nothing. Only the "routes but no fallback" table actually gains one, which is the case where
     * a route that misses would otherwise leave the action unrolled-back despite its own class
     * offering a rollback.
     *
     * @param dynamic the compensation the action's own hook returned; may be {@code null}
     *
     * @return this table, or a copy carrying the supplied fallback
     */
    BoundCompensationRouter<T, C> withFallback(Compensation<T, C> dynamic) {
        if (dynamic == null || fallback != null) {
            return this;
        }
        return new BoundCompensationRouter<>(routes, dynamic);
    }

    /**
     * Resolves the compensation that answers for the supplied failure: the first route whose type
     * and guard both hold, otherwise the fallback.
     *
     * @param error the failure that ended the transition; never {@code null}
     * @param path the qualified path of the action being rolled back, for diagnostics
     *
     * @return the compensation to run, or {@code null} when nothing answers for this failure
     */
    Compensation<T, C> select(Throwable error, ActionPath path) {
        for (BoundCompensationRoute<T, C> route : routes) {
            if (route.matches(error, path)) {
                Loggers.EXECUTION_COMPENSATION.trace(
                    "Compensation route matched, actionPath={}, exceptionType={}",
                    path, route.exceptionType().getName());
                return route.compensation();
            }
        }

        // Only when routes were declared: an action carrying nothing but a plain compensation has
        // no routing decision to report, and this runs once per stack entry per drain.
        if (!routes.isEmpty()) {
            Loggers.EXECUTION_COMPENSATION.trace(
                "No compensation route matched, actionPath={}, fallback={}",
                path, fallback != null);
        }

        return fallback;
    }
}

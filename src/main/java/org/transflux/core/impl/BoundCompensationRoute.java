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

import java.util.function.Predicate;

import static org.transflux.core.Preconditions.requireNotNull;

/**
 * One resolved entry of an action's compensation routing table: the failure this compensation
 * answers for, and the compensation itself.
 * <p>
 * A route matches when the failure is an instance of {@code exceptionType} and the optional guard
 * accepts it. {@link BoundCompensationRouter} tries routes in declaration order and takes the first
 * match, so a route's position is what resolves an overlap between two types.
 *
 * @param exceptionType the failure type this route answers for; never {@code null}
 * @param guard an additional test over the failure, or {@code null} when the type alone decides
 * @param compensation the rollback to run for a matching failure; never {@code null}
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
record BoundCompensationRoute<T, C>(Class<? extends Throwable> exceptionType,
                                    Predicate<Throwable> guard,
                                    Compensation<T, C> compensation) {

    BoundCompensationRoute {
        requireNotNull(exceptionType, "Compensation route exception type");
        requireNotNull(compensation, "Compensation route compensation");
    }

    /**
     * Reports whether this route answers for the supplied failure.
     *
     * <p>A guard that throws is treated as a non-match rather than propagated: this runs inside a
     * rollback that is already unwinding a failure, and letting a second one out would lose the
     * first. Same posture as a compensation that throws mid-drain.
     *
     * @param error the failure that ended the transition; never {@code null}
     * @param path the qualified path of the action being rolled back, for diagnostics
     *
     * @return {@code true} when the type matches and the guard, if any, accepts
     */
    boolean matches(Throwable error, ActionPath path) {
        if (!exceptionType.isInstance(error)) {
            return false;
        }
        if (guard == null) {
            return true;
        }
        try {
            return guard.test(error);
        } catch (Exception ge) {
            // guardErrorType, not errorType: everything else under this logger uses errorType for
            // the transition's failure, and the two must stay tellable apart in a grep.
            Loggers.EXECUTION_COMPENSATION.warn(
                "Compensation route guard threw, actionPath={}, exceptionType={}, guardErrorType={}",
                path, exceptionType.getName(), ge.getClass().getName());
            return false;
        }
    }
}

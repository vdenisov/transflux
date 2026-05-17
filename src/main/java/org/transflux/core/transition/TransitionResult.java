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

package org.transflux.core.transition;

import org.transflux.core.exception.TransfluxValidationException;
import org.transflux.core.operation.StepPath;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents the result of a state machine transition execution.
 * <p>
 * {@code TransitionResult} captures the outcome of executing a transition, including
 * whether it was successful, the source and target states, the transition that was
 * executed, any error that occurred, the steps that ran, the compensations that ran,
 * and the start/completion timestamps.
 *
 * <p>Only business outcomes (failed conditions, failed steps, post-condition
 * violations, unhandled exceptions thrown by user-supplied step or operation code)
 * are reported through this type. Configuration and lookup errors throw
 * {@link TransfluxValidationException} synchronously.
 *
 * <p>The firing-time context object is not stored in the result; callers that need it
 * already have it on their side of the call.
 *
 * <p><b>Example usage:</b>
 * <pre>{@code
 * TransitionResult<Order> result = stateMachine.executeTransition(order, "processing");
 *
 * if (result.isSuccess()) {
 *     log.info("Transitioned {} -> {} in {} via {}",
 *         result.getSourceStateId(), result.getTargetStateId(),
 *         result.getDuration(), result.getTransitionId());
 * } else {
 *     log.error("Transition failed: {}", result.getError().getMessage());
 * }
 * }</pre>
 *
 * @param <T> the type of entity involved in the transition
 */
public class TransitionResult<T> {
    private final boolean success;
    private final T entity;
    private final String sourceStateId;
    private final String targetStateId;
    private final String transitionId;
    private final Throwable error;
    private final List<StepPath> executedStepIds;
    private final List<StepPath> compensatedStepIds;
    private final Instant startedAt;
    private final Instant completedAt;

    private TransitionResult(boolean success,
                             T entity,
                             String sourceStateId,
                             String targetStateId,
                             String transitionId,
                             Throwable error,
                             List<StepPath> executedStepIds,
                             List<StepPath> compensatedStepIds,
                             Instant startedAt,
                             Instant completedAt) {
        this.success = success;
        this.entity = entity;
        this.sourceStateId = sourceStateId;
        this.targetStateId = targetStateId;
        this.transitionId = transitionId;
        this.error = error;
        this.executedStepIds = Collections.unmodifiableList(
                executedStepIds == null ? new ArrayList<>() : new ArrayList<>(executedStepIds));
        this.compensatedStepIds = Collections.unmodifiableList(
                compensatedStepIds == null ? new ArrayList<>() : new ArrayList<>(compensatedStepIds));
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    /**
     * Creates a successful transition result with default timing (both timestamps set to now)
     * and empty step lists.
     */
    public static <T> TransitionResult<T> success(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId) {
        Instant now = Instant.now();
        return success(entity, sourceStateId, targetStateId, transitionId, now, now);
    }

    /**
     * Creates a successful transition result with explicit timestamps and empty step lists.
     */
    public static <T> TransitionResult<T> success(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId,
                                                  Instant startedAt, Instant completedAt) {
        return new TransitionResult<>(true, entity, sourceStateId, targetStateId, transitionId,
                null, null, null, startedAt, completedAt);
    }

    /**
     * Creates a successful transition result with full execution metadata.
     */
    public static <T> TransitionResult<T> success(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId,
                                                  List<StepPath> executedStepIds,
                                                  Instant startedAt, Instant completedAt) {
        return new TransitionResult<>(true, entity, sourceStateId, targetStateId, transitionId,
                null, executedStepIds, null, startedAt, completedAt);
    }

    /**
     * Creates a failed transition result with default timing and empty step lists.
     */
    public static <T> TransitionResult<T> failure(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId,
                                                  Throwable error) {
        Instant now = Instant.now();
        return failure(entity, sourceStateId, targetStateId, transitionId, error, now, now);
    }

    /**
     * Creates a failed transition result with explicit timestamps and empty step lists.
     */
    public static <T> TransitionResult<T> failure(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId,
                                                  Throwable error,
                                                  Instant startedAt, Instant completedAt) {
        return new TransitionResult<>(false, entity, sourceStateId, targetStateId, transitionId,
                error, null, null, startedAt, completedAt);
    }

    /**
     * Creates a failed transition result with full execution metadata, including
     * the steps that ran and the compensations that were executed during rollback.
     */
    public static <T> TransitionResult<T> failure(T entity, String sourceStateId,
                                                  String targetStateId, String transitionId,
                                                  Throwable error,
                                                  List<StepPath> executedStepIds,
                                                  List<StepPath> compensatedStepIds,
                                                  Instant startedAt, Instant completedAt) {
        return new TransitionResult<>(false, entity, sourceStateId, targetStateId, transitionId,
                error, executedStepIds, compensatedStepIds, startedAt, completedAt);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isFailure() {
        return !success;
    }

    public T getEntity() {
        return entity;
    }

    public String getSourceStateId() {
        return sourceStateId;
    }

    public String getTargetStateId() {
        return targetStateId;
    }

    public String getTransitionId() {
        return transitionId;
    }

    public Throwable getError() {
        return error;
    }

    /**
     * Returns the ordered list of step paths that executed during the transition.
     * <p>
     * The list is unmodifiable and reflects steps in the order they ran. Empty when no steps
     * executed. Each {@link StepPath} carries the step's local id and any enclosing
     * nested-operation ids as ordered segments; {@code StepPath.toString()} renders them as
     * the slash-joined qualified path {@code parent-op-id/.../child-step-id}. Top-level
     * steps have single-segment paths.
     *
     * @return the executed step paths; never {@code null}
     */
    public List<StepPath> getExecutedStepIds() {
        return executedStepIds;
    }

    /**
     * Returns the ordered list of step paths whose compensations ran during rollback.
     * <p>
     * The list is unmodifiable and reflects compensations in LIFO order relative to their
     * registration. Empty on successful transitions. Each entry shares the same qualified-path
     * shape as {@link #getExecutedStepIds()}: compensations registered by a step inside a
     * nested operation carry the full enclosing-operation chain; top-level compensations
     * appear under a single-segment path.
     *
     * @return the compensated step paths; never {@code null}
     */
    public List<StepPath> getCompensatedStepIds() {
        return compensatedStepIds;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    /**
     * Returns the duration between {@link #getStartedAt()} and {@link #getCompletedAt()}.
     *
     * @return the transition duration; {@code null} if either timestamp is missing
     */
    public Duration getDuration() {
        if (startedAt == null || completedAt == null) {
            return null;
        }
        return Duration.between(startedAt, completedAt);
    }

    @Override
    public String toString() {
        if (success) {
            return String.format("TransitionResult{success=true, transition=%s, %s -> %s, steps=%d, duration=%s}",
                               transitionId, sourceStateId, targetStateId,
                               executedStepIds.size(), getDuration());
        } else {
            return String.format("TransitionResult{success=false, transition=%s, %s -> %s, steps=%d, compensated=%d, error=%s}",
                               transitionId, sourceStateId, targetStateId,
                               executedStepIds.size(), compensatedStepIds.size(),
                               error != null ? error.getMessage() : "unknown");
        }
    }
}

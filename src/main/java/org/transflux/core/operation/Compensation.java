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

package org.transflux.core.operation;

/**
 * Rollback contract for the effects of an operation or step.
 * <p>
 * A {@code Compensation} undoes the side effects of a previously executed unit of work. The
 * runtime invokes registered compensations in LIFO order when a transition fails after one or
 * more steps have already run, giving each unit of work a chance to clean up before the
 * failure is reported to the caller.
 *
 * <p>The same contract is used for both step-level and operation-level rollback. A
 * {@link Step} returns its compensation from {@link Step#getCompensation(Object, Object)}
 * before {@code execute} runs; the runtime captures that compensation against the step's id
 * and pushes it onto the per-execution rollback stack before invoking {@code execute}. The
 * compensation therefore covers partial side effects from a step whose {@code execute} throws
 * partway through, not just steps that completed successfully.
 *
 * <p>Implementations should be best-effort: a throw from {@code compensate} is logged and the
 * runtime continues with the remaining compensations on the stack.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
@FunctionalInterface
public interface Compensation<T, C> {

    /**
     * Reverses the effects of the unit of work this compensation was registered for.
     *
     * @param entity the entity the unit of work ran against; matches what was passed to the
     *               original {@code execute(...)} call
     * @param context the host-supplied context the unit of work ran against; matches what was
     *                passed to the original {@code execute(...)} call and may be {@code null}
     */
    void compensate(T entity, C context);
}

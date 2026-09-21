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

import org.transflux.core.action.AsyncRejectionPolicy;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * The rejection handler installed on a pool the framework builds, so that
 * {@link AsyncRejectionPolicy#BLOCK} has somewhere to wait.
 * <p>
 * Waiting for capacity is the one answer that cannot be given from the submitting side: by the time
 * {@link ThreadPoolExecutor#execute} has thrown, the chance to enqueue is gone. A rejection handler
 * runs on the submitting thread at the moment of refusal, with the executor in hand, which is
 * exactly where the wait belongs. Every other policy is answered by the caller's own catch, so this
 * handler declines them by throwing - one decision site for three of the four policies, shared with
 * the host-executor path that has no handler of ours at all.
 *
 * <p>Reaching the queue through {@link ThreadPoolExecutor#getQueue()} is the standard shape for this
 * and is safe here for a narrow reason: this handler is installed only on pools the framework built,
 * whose queue is bounded. Enqueueing directly does skip the worker-count recheck
 * {@link ThreadPoolExecutor#execute} performs after its own successful offer, so this handler
 * restores it explicitly - the pool cannot have no workers at the moment it refuses, but it can by
 * the time a long wait ends, since core threads are allowed to time out.
 *
 * <p>Waiting is the designed behaviour of the policy rather than an anomaly, so it is reported at
 * DEBUG per occurrence. Entering that state is the part a host wants to hear about without asking,
 * and is reported once at WARN; sustained saturation belongs to a metrics collector rather than to
 * a line per submission.
 */
final class AsyncRejectionHandler implements RejectedExecutionHandler {

    /**
     * Reports whether the calling thread is running a branch of the state machine that owns this
     * pool - the one case where waiting is a deadlock rather than back-pressure.
     */
    private final BooleanSupplier callerIsOwnWorker;

    /** Guards the one WARN that says this pool started making callers wait. */
    private final AtomicBoolean saturationReported = new AtomicBoolean();

    AsyncRejectionHandler(BooleanSupplier callerIsOwnWorker) {
        this.callerIsOwnWorker = callerIsOwnWorker;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (!(r instanceof AsyncWork work) || work.policy() != AsyncRejectionPolicy.BLOCK) {
            throw refusal(r);
        }

        if (executor.isShutdown()) {
            // Nothing will drain this queue again, so waiting is waiting forever.
            throw refusal(r);
        }

        if (callerIsOwnWorker.getAsBoolean()) {
            // A worker parked here would be waiting for a slot that only it could free: the work
            // queued ahead of it has nowhere else to run. The caller answers this one by running
            // the work inline, which always completes.
            throw refusal(r);
        }

        long startedAt = System.nanoTime();
        try {
            executor.getQueue().put(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refusal(r);
        }

        // Shutdown may have begun while this thread was parked, in which case the work was enqueued
        // onto a queue nobody will drain. Taking it back is what turns that into a refusal the
        // caller's policy can answer, rather than work that silently never runs.
        if (executor.isShutdown() && executor.getQueue().remove(work)) {
            throw refusal(r);
        }

        // The recheck ThreadPoolExecutor.execute does after its own offer, which enqueueing here
        // bypassed. It matters only after a wait long enough for the queue to have drained and the
        // last worker to have timed out, which the fair queue makes unreachable - but the queue is
        // fair only where something can block, and depending on that coupling is not worth a line.
        executor.prestartCoreThread();

        long waitedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        if (saturationReported.compareAndSet(false, true)) {
            Loggers.EXECUTION_ASYNC.warn(
                "Async pool saturated, submissions now wait for capacity, subject={}, waitedMs={}",
                work.subject(), waitedMs);
        } else if (Loggers.EXECUTION_ASYNC.isDebugEnabled()) {
            Loggers.EXECUTION_ASYNC.debug("Async submission blocked, subject={}, waitedMs={}",
                                          work.subject(), waitedMs);
        }
    }

    private static RejectedExecutionException refusal(Runnable r) {
        return new RejectedExecutionException("Async executor refused " + r);
    }
}

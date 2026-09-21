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

import org.transflux.core.exception.TransfluxValidationException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The pool a state machine builds for itself when the host did not supply an executor: how many
 * threads, how deep a queue, and how the threads are made.
 *
 * @param threads the pool size; must be positive
 * @param queueCapacity how many submissions may wait for a thread; must be positive
 * @param threadFactory how worker threads are created, or {@code null} for the default
 */
record AsyncPoolSpec(int threads, int queueCapacity, ThreadFactory threadFactory) {

    /**
     * The pool a host gets without sizing one, scaled to the processors this JVM may use.
     *
     * @return the current default spec
     */
    static AsyncPoolSpec defaults() {
        return defaultsFor(Runtime.getRuntime().availableProcessors());
    }

    /**
     * The default sizing for a given processor count: twice the processors with a floor of four,
     * and ten queue slots per thread.
     *
     * @param processors the processor count to size for
     *
     * @return the default spec for that count
     */
    static AsyncPoolSpec defaultsFor(int processors) {
        // Forked work is mostly waiting on I/O, so one thread per core would starve a small container.
        int threads = Math.max(4, 2 * processors);
        return new AsyncPoolSpec(threads, 10 * threads, null);
    }

    AsyncPoolSpec {
        if (threads <= 0) {
            throw new TransfluxValidationException(
                "Async pool thread count must be positive; got " + threads);
        }
        if (queueCapacity <= 0) {
            throw new TransfluxValidationException(
                "Async pool queue capacity must be positive; got " + queueCapacity);
        }
    }

    /**
     * Builds the pool.
     * <p>
     * Core threads time out, so a state machine that forks rarely holds no threads between bursts
     * while one that forks constantly still gets the full width.
     * <p>
     * The queue is what bounds admission, and the supplied handler is what lets a caller wait for a
     * slot in it. The framework deliberately keeps no bound of its own alongside: a second copy of a
     * number the executor already knows exactly is a copy that can disagree with it, and the
     * direction it disagreed in would be branches refused while the queue had room.
     *
     * @param rejectionHandler what to do when the queue is full; the framework's own, which answers
     *                         only the waiting policy and declines the rest to the caller
     * @param fairQueue whether waiting submitters are served in arrival order, which costs a fair
     *                  lock on every hand-off and is worth it only where something actually waits
     *
     * @return a new pool; the caller owns it and is responsible for shutting it down
     */
    ExecutorService newPool(RejectedExecutionHandler rejectionHandler, boolean fairQueue) {
        ThreadFactory factory = threadFactory != null ? threadFactory : defaultThreadFactory();
        ThreadPoolExecutor pool =
            new ThreadPoolExecutor(threads, threads,
                                   60L, TimeUnit.SECONDS,
                                   new ArrayBlockingQueue<>(queueCapacity, fairQueue),
                                   factory, rejectionHandler);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Daemon threads, so a host that never closes its state machine can still exit; named, so a
     * thread dump says where the work came from.
     *
     * @return the default factory
     */
    private static ThreadFactory defaultThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "transflux-async-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}

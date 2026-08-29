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

    /** What a host gets without saying anything: enough for notification-shaped work. */
    static final AsyncPoolSpec DEFAULT = new AsyncPoolSpec(10, 100, null);

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
     * while one that forks constantly still gets the full width. The rejection handler stays the
     * default abort policy: a caller-runs handler would run a branch on the thread that is midway
     * through the transition which spawned it, which is the one thread it must never occupy.
     *
     * @return a new pool; the caller owns it and is responsible for shutting it down
     */
    ExecutorService newPool() {
        ThreadFactory factory = threadFactory != null ? threadFactory : defaultThreadFactory();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
                                                         60L, TimeUnit.SECONDS,
                                                         new ArrayBlockingQueue<>(queueCapacity),
                                                         factory);
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

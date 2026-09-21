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

/**
 * One piece of work handed to the async executor, carrying the policy that answers for an executor
 * which cannot take it.
 * <p>
 * The policy travels with the task because the only place a refusal can be answered without losing
 * the option to wait is inside the executor's own {@code RejectedExecutionHandler}, which is handed
 * the {@link Runnable} and nothing else.
 *
 * @param task the work itself
 * @param policy what to do when the executor cannot take it, already resolved against the state
 *               machine's default
 * @param subject what the work is, for diagnostics - an action path, or a listener id
 */
record AsyncWork(Runnable task, AsyncRejectionPolicy policy, Object subject) implements Runnable {

    @Override
    public void run() {
        task.run();
    }

    /**
     * Identity, not the record's structural equality.
     * <p>
     * The rejection handler takes a submission back off the queue with
     * {@link java.util.concurrent.BlockingQueue#remove(Object)} when a shutdown races its wait, and
     * {@code remove} deletes <em>an</em> element equal to its argument. Two submissions that
     * compared equal would let that remove the wrong one - refusing a queued submission while the
     * one being refused stays on a queue nobody drains, which is silent loss of work under the one
     * policy whose whole contract is that work is not lost. Structural equality holds only while
     * every task is identity-equal, which is true of the branch task and is not a property to bet
     * on: a submission is a unique event, so it is equal only to itself.
     */
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return String.valueOf(subject);
    }
}

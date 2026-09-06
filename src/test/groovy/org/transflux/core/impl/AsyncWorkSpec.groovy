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

package org.transflux.core.impl

import org.transflux.core.action.AsyncRejectionPolicy
import spock.lang.Specification

/**
 * A submission is equal only to itself, which is what lets the rejection handler take one back off
 * the queue without touching another.
 */
class AsyncWorkSpec extends Specification {

    /** A task with value equality - the shape a shared or record-based Runnable would have. */
    static class ValueEqualTask implements Runnable {
        final String name

        ValueEqualTask(String name) {
            this.name = name
        }

        @Override
        void run() {
        }

        @Override
        boolean equals(Object o) {
            return o instanceof ValueEqualTask && ((ValueEqualTask) o).name == name
        }

        @Override
        int hashCode() {
            return name.hashCode()
        }
    }

    def 'two submissions of equal work are still distinct submissions'() {
        given: 'work that compares equal, which the record component would otherwise inherit'
        def task = new ValueEqualTask('audit')
        def first = new AsyncWork(task, AsyncRejectionPolicy.BLOCK, 'op/audit')
        def second = new AsyncWork(new ValueEqualTask('audit'), AsyncRejectionPolicy.BLOCK,
                                   'op/audit')

        expect: 'queue removal targets one submission, never a sibling that happens to match'
        first != second
        first == first
        !([first] as Set).contains(second)
    }

    def 'a submission names its subject when printed, since that is what a refusal reports'() {
        expect:
        new AsyncWork({} as Runnable, AsyncRejectionPolicy.DROP, 'op/notify').toString() == 'op/notify'
    }
}

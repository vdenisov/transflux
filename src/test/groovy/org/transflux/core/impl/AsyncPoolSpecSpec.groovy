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

import spock.lang.Specification

/**
 * The default pool sizing, which scales with the processors available to the JVM.
 */
class AsyncPoolSpecSpec extends Specification {

    def 'the default sizing for #processors processors is #threads threads and #queueCapacity slots'() {
        when:
        def spec = AsyncPoolSpec.defaultsFor(processors)

        then:
        spec.threads() == threads
        spec.queueCapacity() == queueCapacity
        spec.threadFactory() == null

        where:
        processors || threads | queueCapacity
        1          || 4       | 40
        2          || 4       | 40
        3          || 6       | 60
        8          || 16      | 160
    }

    def 'the default sizing reads the processors this JVM may use'() {
        expect:
        AsyncPoolSpec.defaults() == AsyncPoolSpec.defaultsFor(Runtime.runtime.availableProcessors())
    }
}

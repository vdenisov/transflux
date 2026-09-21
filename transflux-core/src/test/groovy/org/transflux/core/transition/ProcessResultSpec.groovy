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

package org.transflux.core.transition

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

import java.time.Instant

class ProcessResultSpec extends Specification {

    def 'fired carries the trigger id and the transition result'() {
        given:
        def tr = sampleResult()

        when:
        def result = ProcessResult.fired('go', tr)

        then:
        result.fired()
        result.firedTriggerId() == 'go'
        result.result().isPresent()
        result.result().get() == tr
    }

    def 'notFired carries no trigger id and no result'() {
        when:
        def result = ProcessResult.notFired()

        then:
        !result.fired()
        result.firedTriggerId() == null
        result.result().isEmpty()
    }

    def 'fired rejects a blank trigger id'() {
        when:
        ProcessResult.fired('  ', sampleResult())

        then:
        thrown(TransfluxValidationException)
    }

    def 'fired rejects a null result'() {
        when:
        ProcessResult.fired('go', null)

        then:
        thrown(TransfluxValidationException)
    }

    private static TransitionResult<Object> sampleResult() {
        return TransitionResult.success(new Object(), 's1', 's2', 't', [], Instant.now(), Instant.now())
    }
}

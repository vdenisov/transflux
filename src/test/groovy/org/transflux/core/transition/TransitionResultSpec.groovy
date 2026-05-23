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

import spock.lang.Specification

import java.time.Duration
import java.time.Instant

class TransitionResultSpec extends Specification {

    def "success factory should populate basic fields with empty step lists and now-now timestamps"() {
        given:
        def entity = new Object()

        when:
        def result = TransitionResult.success(entity, "S", "T", "tx")

        then:
        result.success
        !result.failure
        result.entity.is(entity)
        result.sourceStateId == "S"
        result.targetStateId == "T"
        result.transitionId == "tx"
        result.error == null
        result.executedStepIds == []
        result.compensatedStepIds == []
        result.startedAt != null
        result.completedAt != null
        result.duration == Duration.ZERO || result.duration.toNanos() >= 0
    }

    def "success factory with timestamps should expose duration"() {
        given:
        def start = Instant.parse("2026-01-01T00:00:00Z")
        def end = Instant.parse("2026-01-01T00:00:05Z")

        when:
        def result = TransitionResult.success("entity", "S", "T", "tx", start, end)

        then:
        result.startedAt == start
        result.completedAt == end
        result.duration == Duration.ofSeconds(5)
    }

    def "success factory with executed step IDs should preserve order"() {
        given:
        def start = Instant.now()
        def steps = [StepPath.of("step-a"), StepPath.of("step-b"), StepPath.of("step-c")]

        when:
        def result = TransitionResult.success("entity", "S", "T", "tx", steps, start, start)

        then:
        result.executedStepIds == [StepPath.of("step-a"), StepPath.of("step-b"), StepPath.of("step-c")]
        result.compensatedStepIds == []
    }

    def "failure factory should capture the error"() {
        given:
        def error = new RuntimeException("kaboom")

        when:
        def result = TransitionResult.failure("entity", "S", "T", "tx", error)

        then:
        !result.success
        result.failure
        result.error.is(error)
        result.executedStepIds == []
        result.compensatedStepIds == []
    }

    def "failure factory with full metadata should preserve step lists"() {
        given:
        def error = new RuntimeException("kaboom")
        def start = Instant.now()
        def executed = [StepPath.of("s1"), StepPath.of("s2")]
        def compensated = [StepPath.of("c-s2"), StepPath.of("c-s1")]

        when:
        def result = TransitionResult.failure(
                "entity", "S", "T", "tx", error, executed, compensated, start, start)

        then:
        result.executedStepIds == [StepPath.of("s1"), StepPath.of("s2")]
        result.compensatedStepIds == [StepPath.of("c-s2"), StepPath.of("c-s1")]
        result.error.is(error)
    }

    def "executedStepIds should be unmodifiable"() {
        given:
        def result = TransitionResult.success("entity", "S", "T", "tx",
                [StepPath.of("s1")], Instant.now(), Instant.now())

        when:
        result.executedStepIds.add(StepPath.of("intruder"))

        then:
        thrown(UnsupportedOperationException)
    }

    def "compensatedStepIds should be unmodifiable"() {
        given:
        def result = TransitionResult.failure(
                "entity", "S", "T", "tx", new RuntimeException(),
                [], [StepPath.of("c1")], Instant.now(), Instant.now())

        when:
        result.compensatedStepIds.add(StepPath.of("intruder"))

        then:
        thrown(UnsupportedOperationException)
    }

    def "factories should defensively copy step list inputs"() {
        given:
        def steps = [StepPath.of("s1"), StepPath.of("s2")] as ArrayList
        def result = TransitionResult.success("entity", "S", "T", "tx",
                steps, Instant.now(), Instant.now())

        when:
        steps.add(StepPath.of("s3"))

        then:
        result.executedStepIds == [StepPath.of("s1"), StepPath.of("s2")]
    }

    def "duration should be null when either timestamp is missing"() {
        when:
        def result = TransitionResult.success("entity", "S", "T", "tx", null, null)

        then:
        result.duration == null
    }

    def "toString of successful result should mention key fields"() {
        when:
        def result = TransitionResult.success("entity", "S", "T", "tx")

        then:
        def s = result.toString()
        s.contains("success=true")
        s.contains("tx")
        s.contains("S -> T")
    }

    def "toString of failed result should mention error message"() {
        when:
        def result = TransitionResult.failure("entity", "S", "T", "tx",
                new RuntimeException("boom"))

        then:
        def s = result.toString()
        s.contains("success=false")
        s.contains("boom")
    }
}

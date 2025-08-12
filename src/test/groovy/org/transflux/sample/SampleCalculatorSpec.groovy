/*
 * Copyright 2025 Victor Denisov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.transflux.sample

import ch.qos.logback.classic.Logger as LBLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Specification
import spock.lang.Unroll

/**
 * TODO: Remove after initial build verification
 */
class SampleCalculatorSpec extends Specification {

    private static final Logger log = LoggerFactory.getLogger(SampleCalculatorSpec)

    private static class ListAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new ArrayList<>()
        @Override
        protected void append(ILoggingEvent eventObject) {
            events.add(eventObject)
        }
    }

    def "should add numbers correctly and log via SLF4J"() {
        given:
        def calcLogger = (LBLogger) LoggerFactory.getLogger(SampleCalculator)
        def appender = new ListAppender()
        appender.start()
        calcLogger.addAppender(appender)
        def calc = new SampleCalculator()

        when:
        def result = calc.add(2, 3)

        then:
        result == 5
        and:
        // Verify two INFO log lines from SampleCalculator
        appender.events.size() >= 2
        appender.events*.formattedMessage.any { it.contains("Adding numbers: a=2, b=3") }
        appender.events*.formattedMessage.any { it.contains("Addition result: 5") }

        cleanup:
        calcLogger.detachAppender(appender)
    }

    def "should multiply numbers correctly and log via SLF4J"() {
        given:
        def calcLogger = (LBLogger) LoggerFactory.getLogger(SampleCalculator)
        def appender = new ListAppender()
        appender.start()
        calcLogger.addAppender(appender)
        def calc = new SampleCalculator()

        when:
        def product = calc.multiply(4, 5)

        then:
        product == 20
        and:
        appender.events.size() >= 2
        appender.events*.formattedMessage.any { it.contains("Multiplying numbers: a=4, b=5") }
        appender.events*.formattedMessage.any { it.contains("Multiplication result: 20") }

        cleanup:
        calcLogger.detachAppender(appender)
    }

    @Unroll
    def "addition with negatives #a + #b = #sum"() {
        given:
        def calc = new SampleCalculator()

        expect:
        calc.add(a, b) == sum

        where:
        a  | b  || sum
        1  | -1 || 0
        -2 | -3 || -5
        -4 | 5  || 1
        0  | 0  || 0
    }
}

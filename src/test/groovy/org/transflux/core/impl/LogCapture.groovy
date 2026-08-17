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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures what the framework logs under a given logger name, so a spec can assert on the tree
 * rather than on a mocked logger. Levels are raised programmatically and restored on {@link #stop},
 * which keeps {@code logback-test.xml} at INFO and the suite's console output readable.
 *
 * <p>Not named {@code *Spec}, so Surefire's include pattern skips it.
 */
class LogCapture {

    private final Logger logger
    private final Level previousLevel
    private final boolean previousAdditive
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()

    private LogCapture(String loggerName, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName)
        this.previousLevel = logger.getLevel()
        this.previousAdditive = logger.isAdditive()

        appender.start()
        logger.addAppender(appender)
        logger.setLevel(level)
        logger.setAdditive(false)
    }

    /** Captures every event at {@code level} or above on {@code loggerName} and its descendants. */
    static LogCapture start(String loggerName, Level level = Level.TRACE) {
        return new LogCapture(loggerName, level)
    }

    List<ILoggingEvent> events() {
        return List.copyOf(appender.list)
    }

    /** Fully formatted messages, with {@code {}} placeholders substituted. */
    List<String> messages() {
        return events().collect { it.formattedMessage }
    }

    List<String> messagesAtOrAbove(Level level) {
        return events().findAll { it.level.isGreaterOrEqual(level) }.collect { it.formattedMessage }
    }

    void stop() {
        logger.detachAppender(appender)
        appender.stop()
        logger.setLevel(previousLevel)
        logger.setAdditive(previousAdditive)
    }
}

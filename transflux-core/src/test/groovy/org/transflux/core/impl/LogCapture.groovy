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
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Captures what the framework logs under a given logger name, so a spec can assert on the tree
 * rather than on a mocked logger. Levels are raised programmatically and restored on {@link #stop},
 * which keeps the suite's console output at whatever {@code logback-test.xml} configures.
 *
 * <p>A capture therefore asserts against a configuration the rest of the suite does not run with,
 * and deliberately so: {@code logback-test.xml} pins {@code org.transflux.build} at WARN to keep
 * several hundred build lines out of the console, and {@link #suspendDescendantLevels} un-pins it
 * for the duration of any capture rooted at or above it. What a spec sees is the framework's own
 * output at the captured level, not what a default host configuration would show.
 *
 * <p>Not named {@code *Spec}, so Surefire's include pattern skips it.
 */
class LogCapture {

    private final Logger logger
    private final Level previousLevel
    private final boolean previousAdditive
    private final Map<Logger, Level> suspendedDescendantLevels = [:]
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>()

    private LogCapture(String loggerName, Level level) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName)
        this.previousLevel = logger.getLevel()
        this.previousAdditive = logger.isAdditive()

        appender.start()
        logger.addAppender(appender)
        logger.setLevel(level)
        logger.setAdditive(false)
        suspendDescendantLevels(loggerName)
    }

    /**
     * Drops any explicit level set on a descendant so it inherits the captured level instead.
     * Without this a capture would silently miss a subtree that {@code logback-test.xml} pins - the
     * descendant's own level is consulted first - and the miss would read as "the framework logged
     * nothing", which is exactly the assertion these specs make.
     *
     * <p>Only covers loggers already instantiated, which is every one configured in XML plus every
     * one the framework has touched. That is enough for a test helper.
     */
    private void suspendDescendantLevels(String loggerName) {
        String prefix = loggerName + '.'
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory()
        context.loggerList.each { Logger candidate ->
            if (candidate.name.startsWith(prefix) && candidate.getLevel() != null) {
                suspendedDescendantLevels[candidate] = candidate.getLevel()
                candidate.setLevel(null)
            }
        }
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
        suspendedDescendantLevels.each { descendant, level -> descendant.setLevel(level) }
        suspendedDescendantLevels.clear()
    }
}

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

package org.transflux.core.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The trace logger tree and the one place a trace line is assembled, so the three listeners agree
 * on key order and on what the options add.
 */
final class TraceLine {

    static final Logger STATE = LoggerFactory.getLogger("org.transflux.trace.state");
    static final Logger TRANSITION = LoggerFactory.getLogger("org.transflux.trace.transition");
    static final Logger ACTION = LoggerFactory.getLogger("org.transflux.trace.action");

    private TraceLine() {
    }

    /**
     * Writes one line if the options' level is enabled, appending the entity label, the duration
     * and the context in that order when the options ask for them.
     *
     * @param logger where to write
     * @param options what to append, and at which level
     * @param message the line's message, without keys
     * @param entity the observed entity
     * @param duration the duration to report, or {@code null} for a line that has none
     * @param context the observed context
     * @param pairs alternating key names and values
     * @param <T> the entity type
     */
    static <T> void write(Logger logger, ExecutionLogging<T> options, String message, T entity,
                          Duration duration, Object context, Object... pairs) {
        if (!logger.isEnabledForLevel(options.level())) {
            return;
        }

        StringBuilder format = new StringBuilder(message);
        List<Object> args = new ArrayList<>(pairs.length / 2 + 3);
        for (int i = 0; i < pairs.length; i += 2) {
            format.append(", ").append(pairs[i]).append("={}");
            args.add(pairs[i + 1]);
        }

        String label = options.labelOf(entity);
        if (label != null) {
            format.append(", entity={}");
            args.add(label);
        }
        if (options.logsTimings() && duration != null) {
            format.append(", durationMs={}");
            args.add(duration.toMillis());
        }
        if (options.logsContext()) {
            format.append(", context={}");
            args.add(context);
        }

        logger.atLevel(options.level()).log(format.toString(), args.toArray());
    }
}

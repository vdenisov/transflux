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

package org.transflux.core.exception;

/**
 * Something that needs a context could not be handed one it accepts: a pass-through dispatch from
 * an incompatible context, a call-site mapper that produced {@code null} or the wrong type, or a
 * {@code ForkableContext} that forked to {@code null}.
 * <p>
 * Only the crossings the definition cannot prove are reported this way. One it can prove wrong
 * fails the build with a {@link TransfluxValidationException}.
 *
 * <p>The subject is usually an action, and then this reaches the caller on the transition's result
 * like any other execution failure. The exception is an async listener whose context would not
 * fork: a listener never gates, so that one is logged and the notification dropped.
 */
public class TransfluxContextException extends TransfluxExecutionException {

    private final String subjectId;

    /**
     * @param subjectId the id of the action - or, for a notification that could not be forked, the
     *                  listener - that could not be given a context
     * @param message the detail message
     */
    public TransfluxContextException(String subjectId, String message) {
        super(message);
        this.subjectId = subjectId;
    }

    /**
     * Returns what could not be given a context: an action id, or a listener id when an async
     * listener's context would not fork.
     *
     * @return the subject's id
     */
    public String getSubjectId() {
        return subjectId;
    }
}

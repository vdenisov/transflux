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

package org.transflux.core.transition;

import org.transflux.core.exception.TransfluxValidationException;

import java.util.ArrayList;
import java.util.List;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

/**
 * Immutable qualified identifier of an executed step.
 * <p>
 * A {@code StepPath} is an ordered, non-empty list of segments. The last (leaf) segment is
 * the step's local id. Any preceding segments are the ids of the nested operations the step
 * ran inside, from outermost to innermost — so a step that ran inside operation {@code outer}
 * which itself ran inside operation {@code grand} surfaces as
 * {@code StepPath.of("grand", "outer", "step-id")}. Top-level steps have a single-segment
 * path, {@code StepPath.of("step-id")}.
 *
 * <p>The {@link #toString()} representation is the segments joined by {@code "/"}, matching
 * the qualified-path format described in
 * {@link org.transflux.core.transition.TransitionResult#getExecutedStepIds()}.
 *
 * @param segments the ordered path segments; never {@code null}, never empty, no segment
 *                 may be {@code null} or blank
 */
public record StepPath(List<String> segments) {

    /**
     * Validates the segment list and stores an unmodifiable copy.
     *
     * @param segments the ordered path segments
     */
    public StepPath {
        requireNotNull(segments, "Step path segments");

        if (segments.isEmpty()) {
            throw new TransfluxValidationException("StepPath segments must not be empty");
        }

        for (String segment : segments) {
            requireNotBlank(segment, "Step path segment");
        }

        segments = List.copyOf(segments);
    }

    /**
     * Convenience factory for a path from a varargs list of segments.
     *
     * @param segments the ordered path segments; must contain at least one non-blank segment
     *
     * @return a fresh {@code StepPath}
     *
     * @throws TransfluxValidationException if {@code segments} is empty or any segment is
     *         {@code null} or blank
     */
    public static StepPath of(String... segments) {
        return new StepPath(List.of(segments));
    }

    /**
     * Returns the leaf segment of this path — the step's local id.
     *
     * @return the leaf segment; never {@code null} or blank
     */
    public String leaf() {
        return segments.get(segments.size() - 1);
    }

    /**
     * Returns the nesting depth of this path. Top-level paths have depth {@code 0}; a step
     * one operation deep has depth {@code 1}; and so on.
     *
     * @return the nesting depth, {@code >= 0}
     */
    public int depth() {
        return segments.size() - 1;
    }

    /**
     * Returns {@code true} if this path is at the top level (no enclosing operations).
     *
     * @return {@code true} for single-segment paths; {@code false} otherwise
     */
    public boolean isTopLevel() {
        return segments.size() == 1;
    }

    /**
     * Returns a new {@code StepPath} extended with the supplied segment. The current path is
     * not modified.
     *
     * @param segment the segment to append; must be non-blank
     *
     * @return a new {@code StepPath} whose segments are this path's segments followed by
     *         {@code segment}
     *
     * @throws TransfluxValidationException if {@code segment} is {@code null} or blank
     */
    public StepPath append(String segment) {
        requireNotBlank(segment, "Step path segment");
        List<String> next = new ArrayList<>(segments.size() + 1);
        next.addAll(segments);
        next.add(segment);
        return new StepPath(next);
    }

    @Override
    public String toString() {
        return String.join("/", segments);
    }
}

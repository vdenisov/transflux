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
 * Immutable qualified identifier of an executed entry.
 * <p>
 * An {@code ActionPath} is an ordered, non-empty list of segments. The last (leaf) segment is
 * the entry's local id. Any preceding segments are the ids of the nested operations it ran
 * inside, from outermost to innermost - so an entry that ran inside operation {@code outer},
 * which itself ran inside operation {@code grand}, surfaces as
 * {@code ActionPath.of("grand", "outer", "leaf-id")}. Top-level entries have a single-segment
 * path, {@code ActionPath.of("leaf-id")}.
 *
 * <p>The {@link #toString()} representation is the segments joined by {@code "/"}, matching
 * the qualified-path format described in
 * {@link org.transflux.core.transition.TransitionResult#getExecutedPath()}.
 *
 * @param segments the ordered path segments; never {@code null}, never empty, no segment
 *                 may be {@code null} or blank
 */
public record ActionPath(List<String> segments) {

    /**
     * Validates the segment list and stores an unmodifiable copy.
     *
     * @param segments the ordered path segments
     */
    public ActionPath {
        requireNotNull(segments, "Action path segments");

        if (segments.isEmpty()) {
            throw new TransfluxValidationException("ActionPath segments must not be empty");
        }

        for (String segment : segments) {
            requireNotBlank(segment, "Action path segment");
        }

        segments = List.copyOf(segments);
    }

    /**
     * Convenience factory for a path from a varargs list of segments.
     *
     * @param segments the ordered path segments; must contain at least one non-blank segment
     *
     * @return a fresh {@code ActionPath}
     *
     * @throws TransfluxValidationException if {@code segments} is empty or any segment is
     *         {@code null} or blank
     */
    public static ActionPath of(String... segments) {
        return new ActionPath(List.of(segments));
    }

    /**
     * Returns the leaf segment of this path - the entry's local id.
     *
     * @return the leaf segment; never {@code null} or blank
     */
    public String leaf() {
        return segments.get(segments.size() - 1);
    }

    /**
     * Returns the nesting depth of this path. Top-level paths have depth {@code 0}; an entry
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
     * Returns a new {@code ActionPath} extended with the supplied segment. The current path is
     * not modified.
     *
     * @param segment the segment to append; must be non-blank
     *
     * @return a new {@code ActionPath} whose segments are this path's segments followed by
     *         {@code segment}
     *
     * @throws TransfluxValidationException if {@code segment} is {@code null} or blank
     */
    public ActionPath append(String segment) {
        requireNotBlank(segment, "Action path segment");
        List<String> next = new ArrayList<>(segments.size() + 1);
        next.addAll(segments);
        next.add(segment);
        return new ActionPath(next);
    }

    @Override
    public String toString() {
        return String.join("/", segments);
    }
}

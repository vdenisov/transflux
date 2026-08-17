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

package org.transflux.core.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework's logger tree, declared in one place so the names are typo-proof and the whole
 * hierarchy is reviewable at a glance.
 *
 * <p>Logger names are <em>virtual packages</em> rather than class names. Implementation classes are
 * concentrated in this package so they can see each other package-privately without widening the
 * public surface, which leaves the real package structure useless as a logging hierarchy — a host
 * configuring it would choose between "all of Transflux" and "one class whose name may be
 * refactored". The names below instead describe how the code would be divided if Java allowed
 * cross-package internal access, so a host can silence {@code org.transflux.execution} wholesale or
 * {@code org.transflux.execution.action} alone.
 *
 * <p>A leaf is a <em>concern</em>, not a class: one class routinely spans several, which is why this
 * is finer-grained than per-class naming rather than a compromise on it. Only the leaves declared
 * here emit — a name is either a grouping level or a logger, never both, so no line ever arrives
 * from a parent category.
 */
final class Loggers {

    /** Ref, context and cycle checks; id claims; definition-time setter overwrites. */
    static final Logger BUILD_VALIDATION = LoggerFactory.getLogger("org.transflux.build.validation");

    /** Scope population, parenting and flattening. */
    static final Logger BUILD_REGISTRY = LoggerFactory.getLogger("org.transflux.build.registry");

    /** Defs to bound records; what each id resolved to, and in which scope. */
    static final Logger BUILD_BINDING = LoggerFactory.getLogger("org.transflux.build.binding");

    /**
     * The build pipeline's own progression: each phase boundary, and the completion line with the
     * resulting counts. The phase boundaries live here rather than on the logger of the phase they
     * announce, so that "the last line emitted names the phase that threw" holds for a host who
     * enabled one leaf rather than the whole {@code org.transflux.build} subtree.
     */
    static final Logger BUILD_LIFECYCLE = LoggerFactory.getLogger("org.transflux.build.lifecycle");

    /** Transition lifecycle: state resolution, outcome, applier. */
    static final Logger EXECUTION_TRANSITION = LoggerFactory.getLogger("org.transflux.execution.transition");

    /** Per-action dispatch, nesting, and call-site context mapping. */
    static final Logger EXECUTION_ACTION = LoggerFactory.getLogger("org.transflux.execution.action");

    /** Pre-, post- and branch-condition evaluation. */
    static final Logger EXECUTION_CONDITION = LoggerFactory.getLogger("org.transflux.execution.condition");

    /** Compensation capture and drain. */
    static final Logger EXECUTION_COMPENSATION = LoggerFactory.getLogger("org.transflux.execution.compensation");

    /** Observer failures. */
    static final Logger EXECUTION_LISTENER = LoggerFactory.getLogger("org.transflux.execution.listener");

    /** Trigger dispatch scans, filters and gates. */
    static final Logger TRIGGER = LoggerFactory.getLogger("org.transflux.trigger");

    private Loggers() {
        // holder class — no instances
    }
}

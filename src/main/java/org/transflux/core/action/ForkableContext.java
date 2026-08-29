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

package org.transflux.core.action;

/**
 * Produces an isolated copy of a context for a forked member, so that a branch and the path that
 * spawned it do not share mutable state.
 * <p>
 * A context implementing this interface is forked once per forked member, on the thread that
 * submits it and before the branch starts, so sibling branches each receive an independent copy.
 * A context that does not implement it is handed to the branch by reference - a legitimate choice
 * for work that only reads, and the reason this is opt-in rather than automatic.
 *
 * <p>The copy strategy belongs to the host: deep, shallow, copy-on-write, or whatever suits the
 * shape of the context. The framework never copies a context reflectively, because lazy proxies,
 * transient fields, captured singletons and framework-managed handles make an implicit deep copy
 * a worse default than an explicit one. It is the implementation's job to make the copy
 * self-consistent - a "copy" that shares a mutable nested object still shares it.
 *
 * <p>A call-site mapper wins over this interface: where a forked member declares one, the mapper
 * produces the branch's context and {@link #fork()} is not consulted.
 *
 * @param <C> the context type produced, normally the implementing type itself
 */
public interface ForkableContext<C> {

    /**
     * Returns a context for one forked member to run against, isolated from the caller's.
     * <p>
     * Invoked on the submitting thread before the branch is handed to the executor, so a throw
     * here fails the transition that was about to spawn the branch, exactly as a failing
     * {@link ContextMapper#mapTo(Object) mapTo} at the same position does.
     *
     * @return the isolated context; must not be {@code null}
     */
    C fork();
}

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

import org.transflux.core.operation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Package-internal implementation of {@link DefaultBranchDef} used by
 * {@link ConditionalStepDefImpl}.
 * <p>
 * This is framework-internal infrastructure; user code should not invoke it directly.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
final class DefaultBranchDefImpl<T, C> implements DefaultBranchDef<T, C> {

    private final List<ActionRef<T, C>> actionRefs = new ArrayList<>();

    DefaultBranchDefImpl() {
    }

    List<ActionRef<T, C>> getActionRefs() {
        return Collections.unmodifiableList(actionRefs);
    }

    @Override
    public DefaultBranchDef<T, C> step(String registeredStepId) {
        actionRefs.add(ActionRef.byId(registeredStepId));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Step<T, C> step) {
        actionRefs.add(ActionRef.inline(id, step));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        actionRefs.add(ActionRef.inline(id, stepClass));
        return this;
    }
}

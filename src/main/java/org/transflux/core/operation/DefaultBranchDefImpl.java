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

package org.transflux.core.operation;

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

    private final List<StepRef<T, C>> stepRefs = new ArrayList<>();

    DefaultBranchDefImpl() {
    }

    List<StepRef<T, C>> getStepRefs() {
        return Collections.unmodifiableList(stepRefs);
    }

    @Override
    public DefaultBranchDef<T, C> step(String registeredStepId) {
        stepRefs.add(StepRef.byId(registeredStepId));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Step<T, C> step) {
        stepRefs.add(StepRef.inline(id, step));
        return this;
    }

    @Override
    public DefaultBranchDef<T, C> step(String id, Class<? extends Step<T, C>> stepClass) {
        stepRefs.add(StepRef.inline(id, stepClass));
        return this;
    }
}

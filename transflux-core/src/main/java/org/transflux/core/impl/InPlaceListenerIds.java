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

import org.transflux.core.exception.TransfluxValidationException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The listener ids a state or a transition declares in place: each refused where it is declared
 * when already taken, and claimed by the state machine once the owner registers.
 */
final class InPlaceListenerIds {

    private final StateMachineDefImpl<?> stateMachineDef;
    private final String ownerLabel;
    private final Map<String, String> sites = new LinkedHashMap<>();

    /**
     * @param stateMachineDef the definition whose listener namespace the ids are checked against
     * @param ownerLabel names the owner in a site, such as {@code state 's'}
     */
    InPlaceListenerIds(StateMachineDefImpl<?> stateMachineDef, String ownerLabel) {
        this.stateMachineDef = stateMachineDef;
        this.ownerLabel = ownerLabel;
    }

    /**
     * Records a listener id declared at one of the owner's hooks.
     *
     * @param listenerId the id
     * @param hook the hook's DSL method, such as {@code onEntry}
     *
     * @throws TransfluxValidationException if the state machine or this owner already declared the id
     */
    void declare(String listenerId, String hook) {
        String site = ownerLabel + " via " + hook;
        stateMachineDef.requireListenerIdFree(listenerId, site);
        String first = sites.get(listenerId);
        if (first != null) {
            throw new TransfluxValidationException(StateMachineDefImpl.duplicateListener(listenerId, first, site));
        }
        sites.put(listenerId, site);
    }

    /**
     * @return listener id to where it is declared, such as {@code state 's' via onEntry}, in declaration order
     */
    Map<String, String> sites() {
        return Collections.unmodifiableMap(sites);
    }
}

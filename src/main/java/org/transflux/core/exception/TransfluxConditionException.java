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
 * A transition's pre- or post-condition did not hold. A pre-condition rejection means nothing ran;
 * a post-condition rejection means the transition ran and was rolled back.
 */
public class TransfluxConditionException extends TransfluxExecutionException {

    /** Which side of the transition the rejecting condition guards. */
    public enum Role {
        PRE_CONDITION,
        POST_CONDITION
    }

    private final String conditionId;
    private final Role role;
    private final String transitionId;

    /**
     * @param conditionId the id of the condition that did not hold
     * @param role which side of the transition it guards
     * @param transitionId the id of the rejected transition
     */
    public TransfluxConditionException(String conditionId, Role role, String transitionId) {
        super((role == Role.PRE_CONDITION ? "Pre-condition '" : "Post-condition '") + conditionId
                  + "' failed for transition '" + transitionId + "'");
        this.conditionId = conditionId;
        this.role = role;
        this.transitionId = transitionId;
    }

    /**
     * @return the id of the condition that did not hold
     */
    public String getConditionId() {
        return conditionId;
    }

    /**
     * @return which side of the transition the condition guards
     */
    public Role getRole() {
        return role;
    }

    /**
     * @return the id of the rejected transition
     */
    public String getTransitionId() {
        return transitionId;
    }
}

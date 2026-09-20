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
 * A choice declared to fail on no match found no branch whose condition held, and
 * has no default branch.
 */
public class TransfluxNoMatchException extends TransfluxExecutionException {

    private final String choiceId;

    /**
     * @param choiceId the id of the choice that matched nothing
     */
    public TransfluxNoMatchException(String choiceId) {
        super("Choice '" + choiceId + "' had no matching branch and no default");
        this.choiceId = choiceId;
    }

    /**
     * @return the id of the choice that matched nothing
     */
    public String getChoiceId() {
        return choiceId;
    }
}

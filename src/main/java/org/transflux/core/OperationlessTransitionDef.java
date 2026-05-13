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

package org.transflux.core;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

public interface OperationlessTransitionDef<T, C> {
    TransitionDef<T, C> withName(String name);
    TransitionDef<T, C> withDescription(String description);

    // Add pre-condition from library, when component library is implemented
    TransitionDef<T, C> addPreCondition(String conditionId);
    // Add pre-condition by predicate
    TransitionDef<T, C> addPreCondition(Predicate<T> preCondition);
    //
    TransitionDef<T, C> addPreCondition(String id, Predicate<T> preCondition);
    // TODO: expression support
    //TransitionDef<T, C> addPreCondition(Expression preConditionExpression);
    //TransitionDef<T, C> addPreCondition(String id, Expression preConditionExpression);

    // TODO: Select condition by id from library, when component library is implemented
    //TransitionDef<T, C> addPostCondition(String conditionId);
    TransitionDef<T, C> addPostCondition(Predicate<T> postCondition);
    TransitionDef<T, C> addPostCondition(String id, Predicate<T> postCondition);
    // TODO: expression support
    //TransitionDef<T, C> addPostCondition(Expression postConditionExpression);
    //TransitionDef<T, C> addPostCondition(String id, Expression postConditionExpression);

    TransitionDef<T, C> addManualTrigger();
    TransitionDef<T, C> addManualTrigger(String id);

    TransitionDef<T, C> addEventTrigger(String id);
    TransitionDef<T, C> addEventTrigger(String id, String eventId);
    TransitionDef<T, C> addEventTrigger(Identifiable event);
    TransitionDef<T, C> addEventTrigger(String id, Identifiable event);
    TransitionDef<T, C> addEventTrigger(BiPredicate<String, T> condition);
    TransitionDef<T, C> addEventTrigger(String id, BiPredicate<String, T> condition);
    // TODO: expression support
    //TransitionDef<T, C> addEventTrigger(Expression conditionExpression);
    //TransitionDef<T, C> addEventTrigger(String id, Expression conditionExpression);

    TransitionDef<T, C> addDataTrigger(String id);
    TransitionDef<T, C> addDataTrigger(Predicate<T> condition);
    TransitionDef<T, C> addDataTrigger(String id, Predicate<T> condition);
    // TODO: expression support
    //TransitionDef<T, C> addDataTrigger(Expression conditionExpression);
    //TransitionDef<T, C> addDataTrigger(String id, Expression conditionExpression);
}

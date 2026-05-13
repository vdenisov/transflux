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

public interface OperationlessTransitionDef<T> {
    TransitionDef<T> withName(String name);
    TransitionDef<T> withDescription(String description);

    // Add pre-condition from library, when component library is implemented
    TransitionDef<T> addPreCondition(String conditionId);
    // Add pre-condition by predicate
    TransitionDef<T> addPreCondition(Predicate<T> preCondition);
    //
    TransitionDef<T> addPreCondition(String id, Predicate<T> preCondition);
    // TODO: expression support
    //TransitionDef<T> addPreCondition(Expression preConditionExpression);
    //TransitionDef<T> addPreCondition(String id, Expression preConditionExpression);

    // TODO: Select condition by id from library, when component library is implemented
    //TransitionDef<T> addPostCondition(String conditionId);
    TransitionDef<T> addPostCondition(Predicate<T> postCondition);
    TransitionDef<T> addPostCondition(String id, Predicate<T> postCondition);
    // TODO: expression support
    //TransitionDef<T> addPostCondition(Expression postConditionExpression);
    //TransitionDef<T> addPostCondition(String id, Expression postConditionExpression);

    TransitionDef<T> addManualTrigger();
    TransitionDef<T> addManualTrigger(String id);

    TransitionDef<T> addEventTrigger(String id);
    TransitionDef<T> addEventTrigger(String id, String eventId);
    TransitionDef<T> addEventTrigger(Identifiable event);
    TransitionDef<T> addEventTrigger(String id, Identifiable event);
    TransitionDef<T> addEventTrigger(BiPredicate<String, T> condition);
    TransitionDef<T> addEventTrigger(String id, BiPredicate<String, T> condition);
    // TODO: expression support
    //TransitionDef<T> addEventTrigger(Expression conditionExpression);
    //TransitionDef<T> addEventTrigger(String id, Expression conditionExpression);

    TransitionDef<T> addDataTrigger(String id);
    TransitionDef<T> addDataTrigger(Predicate<T> condition);
    TransitionDef<T> addDataTrigger(String id, Predicate<T> condition);
    // TODO: expression support
    //TransitionDef<T> addDataTrigger(Expression conditionExpression);
    //TransitionDef<T> addDataTrigger(String id, Expression conditionExpression);
}

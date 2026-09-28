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

package com.example.conditions

import org.transflux.core.condition.Condition
import org.transflux.core.transition.Transition

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class CheckoutFulfilledCondition<T, C> implements Condition<T, C> {
    @Override
    boolean test(T entity, C context, Transition transition) {
        return true
    }
}

class HasPaymentMethodCondition<T, C> implements Condition<T, C> {
    @Override
    boolean test(T entity, C context, Transition transition) {
        return true
    }
}

class MilestonesActivatedCondition<T, C> implements Condition<T, C> {
    @Override
    boolean test(T entity, C context, Transition transition) {
        return true
    }
}

class PaymentMethodPresentCondition<T, C> implements Condition<T, C> {
    @Override
    boolean test(T entity, C context, Transition transition) {
        return true
    }
}

class PaymentMethodValidCondition<T, C> implements Condition<T, C> {
    @Override
    boolean test(T entity, C context, Transition transition) {
        return true
    }
}

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

package com.example.predicates

import java.util.function.BiPredicate
import java.util.function.Predicate

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class CriticalPriorityPredicate<A, B> implements BiPredicate<A, B> {
    @Override
    boolean test(A first, B second) {
        return true
    }
}

class HighPriorityPredicate<A, B> implements BiPredicate<A, B> {
    @Override
    boolean test(A first, B second) {
        return true
    }
}

class PaymentMethodCurrentPredicate<A, B> implements BiPredicate<A, B> {
    @Override
    boolean test(A first, B second) {
        return true
    }
}

class RecoverableErrorPredicate<E> implements Predicate<E> {
    @Override
    boolean test(E failure) {
        return true
    }
}

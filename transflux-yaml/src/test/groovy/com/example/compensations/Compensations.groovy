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

package com.example.compensations

import org.transflux.core.action.Compensation

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class GeneralCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

class ReconcileLaterCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

class RecoverableCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

class RefundCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

class RefundPartialFeesCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

class ValidationCompensation<T, C> implements Compensation<T, C> {
    @Override
    void compensate(T entity, C context) {
    }
}

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

package com.example.listeners

import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionListener
import org.transflux.core.state.StateChange
import org.transflux.core.state.StateListener
import org.transflux.core.transition.TransitionExecution
import org.transflux.core.transition.TransitionListener

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class ActivationAuditListener<T, C> implements TransitionListener<T, C> {
    @Override
    void onTransition(T entity, C context, TransitionExecution<T> execution) {
    }
}

class ChargeAuditListener<T, C> implements ActionListener<T, C> {
    @Override
    void onAction(T entity, C context, ActionExecution execution) {
    }
}

class RedactedCaptureListener<T, C> implements ActionListener<T, C> {
    @Override
    void onAction(T entity, C context, ActionExecution execution) {
    }
}

class StateAuditListener<T> implements StateListener<T> {
    @Override
    void onState(T entity, Object context, StateChange change) {
    }
}

class SubscriptionActivatedListener<T> implements StateListener<T> {
    @Override
    void onState(T entity, Object context, StateChange change) {
    }
}

class TransitionAuditListener<T, C> implements TransitionListener<T, C> {
    @Override
    void onTransition(T entity, C context, TransitionExecution<T> execution) {
    }
}

class TransitionStartListener<T, C> implements TransitionListener<T, C> {
    @Override
    void onTransition(T entity, C context, TransitionExecution<T> execution) {
    }
}

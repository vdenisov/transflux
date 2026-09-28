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

package com.example.actions

import org.transflux.core.action.Action
import org.transflux.core.transition.ExecutingTransition

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class AccountManagerAlertStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class AccountManagerNotificationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class ActivateSubscriptionStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class BusinessHoursProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class CancelSubscriptionStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class EscalateStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class ExpeditedProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class FinalizeActivationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class HighPriorityStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class LockResourcesStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class ManagementNotificationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class MediumPriorityStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class NotifyPaymentIssueStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class PremiumNotificationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class PriorityProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class SendWelcomeEmailStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class StandardNotificationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class StandardProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class UrgentNotificationStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class UrgentProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

class VipProcessingStep<T, C> implements Action<T, C> {
    @Override
    void execute(T entity, C context, ExecutingTransition<T, C> transition) {
    }
}

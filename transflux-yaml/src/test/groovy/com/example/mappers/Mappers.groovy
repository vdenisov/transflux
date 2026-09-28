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

package com.example.mappers

import org.transflux.core.action.ContextMapper

// Inert classes the specification's YAML examples name, generic so the loader leaves their types to the runtime.

class BillingFromActivationMapper<P, N> implements ContextMapper<P, N> {
    @Override
    N mapTo(P parentContext) {
        return null
    }
}

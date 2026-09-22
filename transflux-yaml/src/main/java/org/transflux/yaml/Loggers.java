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

package org.transflux.yaml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The loader's leaves of the framework's logger tree. Names are virtual packages describing a
 * concern, as in the core module's holder, and only the leaves declared here emit.
 */
final class Loggers {

    /** Reading documents: what was parsed, from where. */
    static final Logger YAML_PARSE = LoggerFactory.getLogger("org.transflux.yaml.parse");

    /** Mapping documents onto definitions: which def call each entry became, and what went unchecked. */
    static final Logger YAML_BINDING = LoggerFactory.getLogger("org.transflux.yaml.binding");

    private Loggers() {
    }
}

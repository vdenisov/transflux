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

package org.transflux.yaml.source;

/**
 * How a {@link FileSystemDefinitionSource} treats symbolic links beneath its root. The root itself
 * may always be a link.
 */
public enum SymlinkPolicy {

    /** Follow links whose target stays under the root; refuse one that leads out of it. The default. */
    WITHIN_ROOT,

    /** Follow every link, wherever it leads. */
    FOLLOW,

    /**
     * Refuse any identifier whose path passes through a link, a Windows junction included, or any
     * other special entry - another reparse point such as a cloud placeholder, a device, a pipe.
     */
    REJECT
}

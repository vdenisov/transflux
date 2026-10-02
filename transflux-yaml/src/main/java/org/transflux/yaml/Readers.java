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

/**
 * The entry readers one load shares across every document it reads, built once per load by
 * {@link #of}, so the component sections and the state machine section share one wiring.
 *
 * @param classes how the documents' class names become classes and instances
 * @param entityType the definition's entity type, which each class is checked against
 * @param sites where this load's ids were first declared
 * @param conditions the condition descriptor grammar
 * @param listeners listener pool entries, hook blocks and disables
 * @param actions action pool entries and {@code actions:} lists
 * @param triggers trigger pool entries and a transition's {@code triggers:} entries
 * @param expressions the expressions the loader evaluates itself
 */
record Readers(Classes classes, Class<?> entityType, DeclarationSites sites, ConditionDescriptors conditions,
               ListenerEntries listeners, ActionEntries actions, TriggerEntries triggers, Expressions expressions) {

    /**
     * Builds the readers for one load.
     *
     * @param classes how the documents' class names become classes and instances
     * @param entityType the definition's entity type
     * @param sites where this load's ids were first declared
     *
     * @return the readers
     */
    static Readers of(Classes classes, Class<?> entityType, DeclarationSites sites) {
        Expressions expressions = new Expressions(classes.classLoader());
        ConditionDescriptors conditions = new ConditionDescriptors(classes, entityType);
        ListenerEntries listeners = new ListenerEntries(classes, entityType, sites);
        ActionEntries actions = new ActionEntries(classes, entityType, conditions,
            new ActionKeys(classes, entityType, listeners, expressions), sites, expressions);
        TriggerEntries triggers = new TriggerEntries(classes, entityType, conditions, sites);
        return new Readers(classes, entityType, sites, conditions, listeners, actions, triggers, expressions);
    }
}

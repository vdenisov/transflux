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

import org.transflux.core.action.ActionDef;
import org.transflux.core.action.AsyncRejectionPolicy;
import org.transflux.core.action.Compensation;
import org.transflux.core.action.CompensationRouteDef;
import org.transflux.yaml.ListenerEntries.Category;
import org.transflux.yaml.ListenerEntries.Hook;
import org.transflux.yaml.TypeArguments.Expected;
import org.yaml.snakeyaml.nodes.Node;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The keys every action declaration accepts around its own content, whatever its form: metadata,
 * compensation and its routes, the rejection policy, and listeners.
 */
final class ActionKeys {

    private final Classes classes;
    private final Class<?> entityType;
    private final ListenerEntries listeners;

    ActionKeys(Classes classes, Class<?> entityType, ListenerEntries listeners) {
        this.classes = classes;
        this.entityType = entityType;
        this.listeners = listeners;
    }

    /**
     * Applies the shared keys of an action declaration to its def.
     *
     * @param map the declaration
     * @param def the action's def, inside its configurer
     * @param declared the context the action declared; {@code null} when it declared none
     *
     * @throws DefinitionLoadException when a key is not valid
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void apply(NodeMap map, ActionDef def, Class<?> declared) {
        // What is declared on an action is typed against its def's context, which is Object when none was declared.
        Class<?> context = declared == null ? Object.class : declared;
        ComponentSections.metadata(map, def::withName, def::withDescription);
        AsyncRejectionPolicy policy = map.optionalEnum("onRejection", AsyncRejectionPolicy.class);
        if (policy != null) {
            map.at(map.requiredNode("onRejection"), () -> def.withAsyncRejectionPolicy(policy));
        }

        if (map.optionalNode("compensation") != null) {
            Compensation compensation = compensation(map, context);
            map.at(map.requiredNode("compensation"), () -> def.withCompensation(compensation));
        }

        List<Node> routes = map.optionalList("errorHandling");
        if (routes != null) {
            routes.forEach(route -> route(map, route, def, context));
        }

        listeners.hooks(map, Category.ACTION, context, List.of(
            new Hook("onStart", def::onStart, (id, cfg) -> def.onStart(id, (Consumer) cfg)),
            new Hook("onComplete", def::onComplete, (id, cfg) -> def.onComplete(id, (Consumer) cfg)),
            new Hook("onError", def::onError, (id, cfg) -> def.onError(id, (Consumer) cfg))));
        ListenerEntries.disables(map, def::disableAllGlobalListeners, def::disableGlobalListeners);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void route(NodeMap owner, Node node, ActionDef def, Class<?> context) {
        NodeMap route = NodeMap.of(owner.document(), node, owner.declarationPath(), "an errorHandling entry");
        Class<? extends Throwable> exception = (Class<? extends Throwable>) classes.requiredClass(route, "exception",
            Throwable.class);
        CompensationRouteDef opened = route.at(route.requiredNode("exception"), () -> def.forException(exception));

        NodeMap guard = route.optionalMap("guard");
        if (guard != null) {
            Predicate<?> predicate = guard.exactlyOneOf("class", "expression").equals("class")
                ? classes.instantiate(guard, "class", Predicate.class, Expected.exactly(exception))
                : Expressions.guard(guard);
            guard.rejectUnknownKeys();
            route.at(route.requiredNode("guard"), () -> opened.matching(predicate));
        }

        Compensation compensation = compensation(route, context);
        route.at(route.requiredNode("compensation"), () -> opened.withCompensation(compensation));
        route.rejectUnknownKeys();
    }

    private Compensation<?, ?> compensation(NodeMap map, Class<?> context) {
        return classes.instantiate(map, "compensation", Compensation.class,
            Expected.superOf(entityType), Expected.exactly(context));
    }
}

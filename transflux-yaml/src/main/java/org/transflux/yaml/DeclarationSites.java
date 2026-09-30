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

import org.transflux.core.exception.TransfluxValidationException;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.nodes.Node;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Where each id one load registers was first declared, across every document it reads, so a
 * definition call refusing an id as taken can name the earlier declaration as well as its own.
 * Core does the refusing; this only adds the location.
 */
final class DeclarationSites {

    /**
     * The id sets core checks a declaration against when it is made. Actions, conditions and
     * mappers share one: a registration of any of the three is refused against the other two.
     */
    enum Namespace { COMPONENT, TRIGGER, LISTENER, STATE, TRANSITION }

    private final Map<Namespace, Map<String, String>> sites = new EnumMap<>(Namespace.class);

    /**
     * Makes a definition call that claims an id, as {@link NodeMap#at} does, and records where the
     * id was declared.
     *
     * @param map the declaration
     * @param at the node the id is written at
     * @param namespace the namespace the id is claimed in
     * @param id the id
     * @param call the call
     * @param <R> what the call returns
     *
     * @return what the call returned
     *
     * @throws DefinitionLoadException wrapping a rejection the call threw, which names the first
     *         declaration of {@code id} when this load has seen one
     */
    <R> R declare(NodeMap map, Node at, Namespace namespace, String id, Supplier<R> call) {
        return map.at(at, () -> claim(map, at, namespace, id, call));
    }

    /**
     * {@link #declare} for a caller that attributes a rejection to a line of its own choosing.
     *
     * @param map the declaration
     * @param at the node the id is written at
     * @param namespace the namespace the id is claimed in
     * @param id the id
     * @param call the call
     * @param <R> what the call returns
     *
     * @return what the call returned
     *
     * @throws TransfluxValidationException a rejection the call threw, naming the first
     *         declaration of {@code id} when this load has seen one
     */
    <R> R claim(NodeMap map, Node at, Namespace namespace, String id, Supplier<R> call) {
        Map<String, String> declared = sites.computeIfAbsent(namespace, ignored -> new HashMap<>());
        String first = declared.get(id);
        R result;
        try {
            result = call.get();
        } catch (DefinitionLoadException e) {
            throw e;
        } catch (TransfluxValidationException e) {
            if (first == null) {
                throw e;
            }
            String message = e.getMessage();
            // Naming the id keeps the site from reading as the cause of a rejection about something else.
            message = message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
            throw new TransfluxValidationException(message + ". '" + id + "' was first declared at " + first, e);
        }
        Mark mark = at.getStartMark();
        declared.putIfAbsent(id,
            map.document().identifier() + ":" + (mark.getLine() + 1) + ":" + (mark.getColumn() + 1));
        return result;
    }
}

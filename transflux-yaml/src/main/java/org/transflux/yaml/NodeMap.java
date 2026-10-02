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
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * One YAML mapping being read, together with where it sits. Every key a reader asks for, present
 * or not, is one the mapping may hold, which is what lets {@link #rejectUnknownKeys()} name the
 * keys allowed there without a second list to keep in step.
 */
final class NodeMap {

    private final Document document;
    private final MappingNode node;
    private final String declarationPath;
    private final Map<String, NodeTuple> entries = new LinkedHashMap<>();
    private final Set<String> asked = new LinkedHashSet<>();

    private NodeMap(Document document, MappingNode node, String declarationPath) {
        this.document = document;
        this.node = node;
        this.declarationPath = declarationPath;
        // Document.parse already refused non-scalar and duplicate keys.
        node.getValue().forEach(entry -> entries.put(((ScalarNode) entry.getKeyNode()).getValue(), entry));
    }

    /**
     * Reads a node as a mapping.
     *
     * @param document the document the node is in
     * @param node the node
     * @param declarationPath the enclosing declarations; nullable
     * @param what what the mapping is, for the error when it is not one
     *
     * @return the mapping
     *
     * @throws DefinitionLoadException when the node is not a mapping
     */
    static NodeMap of(Document document, Node node, String declarationPath, String what) {
        if (!(node instanceof MappingNode mapping)) {
            throw document.error(node, declarationPath, what + " must be a mapping");
        }
        return new NodeMap(document, mapping, declarationPath);
    }

    /**
     * @param label the declaration the mapping's contents sit inside, such as {@code transition 't'}
     *
     * @return this mapping, read with {@code label} appended to its declaration path
     */
    NodeMap within(String label) {
        NodeMap nested = new NodeMap(document, node, declarationPath == null ? label : declarationPath + " > " + label);
        nested.asked.addAll(asked);
        return nested;
    }

    Document document() {
        return document;
    }

    String declarationPath() {
        return declarationPath;
    }

    MappingNode node() {
        return node;
    }

    /**
     * Admits every key the mapping holds, for a mapping whose keys are data rather than grammar.
     *
     * @return the keys, in document order
     */
    List<String> keys() {
        asked.addAll(entries.keySet());
        return List.copyOf(entries.keySet());
    }

    /**
     * @param key a key the mapping holds
     *
     * @return the node of the key itself, for a problem with the key rather than its value
     */
    Node keyNode(String key) {
        return entries.get(key).getKeyNode();
    }

    /**
     * @param key the key
     *
     * @return the key's value node, or {@code null} when the key is absent or its value is null
     */
    Node optionalNode(String key) {
        asked.add(key);
        NodeTuple entry = entries.get(key);
        if (entry == null || Tag.NULL.equals(entry.getValueNode().getTag())) {
            return null;
        }
        return entry.getValueNode();
    }

    /**
     * @param key the key
     *
     * @return the key's value node
     *
     * @throws DefinitionLoadException when the key is absent or its value is null
     */
    Node requiredNode(String key) {
        Node value = optionalNode(key);
        if (value == null) {
            NodeTuple entry = entries.get(key);
            throw entry == null
                ? error(node, "'" + key + "' is required")
                : error(entry.getKeyNode(), "'" + key + "' requires a value");
        }
        return value;
    }

    /**
     * Reads a declaration's id, so that a missing one is reported under what is being declared.
     *
     * @param kind what the mapping declares, such as {@code step}
     *
     * @return the id
     *
     * @throws DefinitionLoadException when the id is absent or null
     */
    String requiredId(String kind) {
        // Asked here as well as read, so the key is allowed in this mapping and not only in the label's copy.
        if (optionalNode("id") instanceof ScalarNode scalar) {
            return scalar.getValue();
        }
        return within(kind).requiredString("id");
    }

    /**
     * Reads a scalar that names something by its id, refusing one that names nothing.
     *
     * @param node the scalar
     * @param problem the refusal of a node that names nothing
     *
     * @return the id
     *
     * @throws DefinitionLoadException when the node is not a scalar, or is null or blank
     */
    String referenceId(Node node, String problem) {
        // Read as text, a null would be a reference to the id '~' or 'null' nobody wrote.
        if (!(node instanceof ScalarNode scalar) || Tag.NULL.equals(scalar.getTag()) || scalar.getValue().isBlank()) {
            throw error(node, problem);
        }
        return scalar.getValue();
    }

    /**
     * Reads a list that must hold at least one item.
     *
     * @param key the list's key
     * @param what what the list holds, for the refusal of an empty one, such as {@code action}
     *
     * @return the items
     *
     * @throws DefinitionLoadException when the key is absent, not a list, or the list is empty
     */
    List<Node> requiredNonEmptyList(String key, String what) {
        List<Node> items = requiredList(key);
        if (items.isEmpty()) {
            throw error(requiredNode(key), "'" + key + "' must hold at least one " + what);
        }
        return items;
    }

    String requiredString(String key) {
        return scalar(key, requiredNode(key), "a string");
    }

    String optionalString(String key) {
        Node value = optionalNode(key);
        return value == null ? null : scalar(key, value, "a string");
    }

    Boolean optionalBoolean(String key) {
        Node value = optionalNode(key);
        if (value == null) {
            return null;
        }
        // Exactly the two literals: YAML 1.1 would also read yes / no / on / off as booleans.
        String text = scalar(key, value, "true or false");
        if (!text.equals("true") && !text.equals("false")) {
            throw error(value, "'" + key + "' must be true or false, not '" + text + "'");
        }
        return Boolean.valueOf(text);
    }

    Integer optionalInt(String key) {
        Node value = optionalNode(key);
        if (value == null) {
            return null;
        }
        String text = scalar(key, value, "a whole number");
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw error(value, "'" + key + "' must be a whole number, not '" + text + "'");
        }
    }

    <E extends Enum<E>> E optionalEnum(String key, Class<E> type) {
        Node value = optionalNode(key);
        if (value == null) {
            return null;
        }
        String allowed = Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
        String text = scalar(key, value, "one of " + allowed);
        try {
            return Enum.valueOf(type, text);
        } catch (IllegalArgumentException e) {
            throw error(value, "'" + key + "' must be one of " + allowed + ", not '" + text + "'");
        }
    }

    NodeMap requiredMap(String key) {
        return of(document, requiredNode(key), declarationPath, "'" + key + "'");
    }

    NodeMap optionalMap(String key) {
        Node value = optionalNode(key);
        return value == null ? null : of(document, value, declarationPath, "'" + key + "'");
    }

    /**
     * @param key the key
     * @param label what the block is, appended to its declaration path as {@link #within} does
     *
     * @return the key's value as a mapping, labelled, or {@code null} when the key is absent
     *
     * @throws DefinitionLoadException when the value is not a mapping
     */
    NodeMap optionalMap(String key, String label) {
        NodeMap map = optionalMap(key);
        return map == null ? null : map.within(label);
    }

    /**
     * @param key the key
     *
     * @return the list's elements, or {@code null} when the key is absent
     *
     * @throws DefinitionLoadException when the value is not a list
     */
    List<Node> optionalList(String key) {
        Node value = optionalNode(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof SequenceNode sequence)) {
            throw error(value, "'" + key + "' must be a list");
        }
        return sequence.getValue();
    }

    /**
     * @param key the key
     *
     * @return the list's elements
     *
     * @throws DefinitionLoadException when the key is absent, null, or not a list
     */
    List<Node> requiredList(String key) {
        requiredNode(key);
        return optionalList(key);
    }

    /**
     * Tells whether the mapping holds a key without admitting it, for a key a position refuses
     * with an explanation rather than as unknown.
     *
     * @param key the key
     *
     * @return whether the key is written, whatever its value
     */
    boolean holds(String key) {
        return entries.containsKey(key);
    }

    /**
     * @param keys the alternatives
     *
     * @return the one alternative present
     *
     * @throws DefinitionLoadException when none or several are present
     */
    String exactlyOneOf(String... keys) {
        List<String> present = Arrays.stream(keys).filter(key -> optionalNode(key) != null).toList();
        if (present.size() == 1) {
            // The alternatives not taken are not allowed beside it, so no unknown-key error may list them.
            Arrays.stream(keys).filter(key -> !entries.containsKey(key)).forEach(asked::remove);
            return present.get(0);
        }
        String alternatives = Arrays.stream(keys).map(key -> "'" + key + "'").collect(Collectors.joining(", "));
        if (present.isEmpty()) {
            throw error(node, "exactly one of " + alternatives + " is required");
        }
        throw error(entries.get(present.get(1)).getKeyNode(),
            "exactly one of " + alternatives + " is allowed; found both '" + present.get(0)
                + "' and '" + present.get(1) + "'");
    }

    /**
     * Refuses the first key no reader asked for. Call once every key the position allows was read.
     *
     * @throws DefinitionLoadException naming the unknown key and the keys allowed here
     */
    void rejectUnknownKeys() {
        for (Map.Entry<String, NodeTuple> entry : entries.entrySet()) {
            if (!asked.contains(entry.getKey())) {
                throw error(entry.getValue().getKeyNode(), "unknown key '" + entry.getKey() + "'; expected "
                    + (asked.isEmpty() ? "none" : asked.size() == 1 ? "only " + asked.iterator().next()
                    : "one of " + String.join(", ", asked)));
            }
        }
    }

    /**
     * Runs a definition call made on behalf of a node, so a rejection it throws is reported at the
     * line that caused it.
     *
     * @param at the node the call was made for
     * @param call the call
     * @param <R> what the call returns
     *
     * @return what the call returned
     *
     * @throws DefinitionLoadException wrapping a {@link TransfluxValidationException} the call threw
     */
    <R> R at(Node at, Supplier<R> call) {
        try {
            return call.get();
        } catch (DefinitionLoadException e) {
            throw e;
        } catch (TransfluxValidationException e) {
            throw error(at, e.getMessage(), e);
        }
    }

    DefinitionLoadException error(Node at, String problem) {
        return document.error(at, declarationPath, problem);
    }

    DefinitionLoadException error(Node at, String problem, Throwable cause) {
        return document.error(at, declarationPath, problem, cause);
    }

    private String scalar(String key, Node value, String expected) {
        if (!(value instanceof ScalarNode scalar)) {
            throw error(value, "'" + key + "' must be " + expected);
        }
        return scalar.getValue();
    }
}

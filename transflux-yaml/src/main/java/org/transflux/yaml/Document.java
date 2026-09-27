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

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.Reader;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * One parsed definition document: its node tree, with every node's position, and the identity
 * errors against it are reported under - the imports that reached it included.
 */
record Document(List<String> importChain, String identifier, String location, Node root) {

    private static final Set<Tag> PLAIN_TAGS = Set.of(
        Tag.STR, Tag.INT, Tag.BOOL, Tag.NULL, Tag.FLOAT, Tag.TIMESTAMP, Tag.SEQ, Tag.MAP);

    private static final String CORE_TAG_PREFIX = "tag:yaml.org,2002:";

    /**
     * Parses exactly one document and refuses what the grammar does not use.
     *
     * @param importChain the identifiers of the documents whose imports reached this one, the root
     *        first; empty for the root
     * @param identifier the identifier the document was opened under
     * @param location where the source found it; nullable
     * @param reader the document's text
     *
     * @return the parsed document
     *
     * @throws DefinitionLoadException when the text is not YAML, holds other than one document, or
     *         uses an anchor, an alias, a merge key, a tag or a duplicate key
     */
    static Document parse(List<String> importChain, String identifier, String location, Reader reader) {
        Iterator<Node> documents;
        Node root;
        try {
            LoaderOptions options = new LoaderOptions();
            // Composing constructs nothing, so admit every tag here and refuse them below with one message.
            options.setTagInspector(tag -> true);
            documents = new Yaml(options).composeAll(reader).iterator();
            if (!documents.hasNext()) {
                throw new DefinitionLoadException(importChain, identifier, location, null, null, null,
                    "the document is empty", null);
            }
            root = documents.next();
            if (documents.hasNext()) {
                Node second = documents.next();
                throw new Document(importChain, identifier, location, root).error(second, null,
                    "a definition is one YAML document; this is a second one");
            }
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            throw new DefinitionLoadException(importChain, identifier, location,
                mark == null ? null : mark.getLine() + 1, mark == null ? null : mark.getColumn() + 1,
                null, "not valid YAML: " + e.getProblem(), e);
        } catch (YAMLException e) {
            throw new DefinitionLoadException(importChain, identifier, location, null, null, null,
                "cannot be read: " + e.getMessage(), e);
        }
        Document document = new Document(importChain, identifier, location, root);
        document.refuseUnsupported(root);
        return document;
    }

    /**
     * An error at a node.
     *
     * @param node the node the problem is at
     * @param declarationPath the enclosing declarations; nullable
     * @param problem what was wrong
     *
     * @return the exception, for the caller to throw
     */
    DefinitionLoadException error(Node node, String declarationPath, String problem) {
        return error(node, declarationPath, problem, null);
    }

    /**
     * An error at a node, caused by another failure.
     *
     * @param node the node the problem is at
     * @param declarationPath the enclosing declarations; nullable
     * @param problem what was wrong
     * @param cause the failure behind it; nullable
     *
     * @return the exception, for the caller to throw
     */
    DefinitionLoadException error(Node node, String declarationPath, String problem, Throwable cause) {
        Mark mark = node.getStartMark();
        return new DefinitionLoadException(importChain, identifier, location, mark.getLine() + 1,
            mark.getColumn() + 1, declarationPath, problem, cause);
    }

    private void refuseUnsupported(Node node) {
        // An alias needs an anchor declared before it, so refusing anchors refuses aliases too.
        if (node.getAnchor() != null) {
            throw error(node, null, "anchors and aliases are not supported ('&" + node.getAnchor() + "')");
        }
        if (!PLAIN_TAGS.contains(node.getTag())) {
            throw error(node, null, "tag '" + shortTag(node.getTag()) + "' is not supported");
        }
        if (node instanceof MappingNode mapping) {
            Set<String> keys = new HashSet<>();
            for (NodeTuple entry : mapping.getValue()) {
                Node key = entry.getKeyNode();
                if (Tag.MERGE.equals(key.getTag())) {
                    throw error(key, null, "merge keys ('<<') are not supported");
                }
                refuseUnsupported(key);
                if (!(key instanceof ScalarNode scalar)) {
                    throw error(key, null, "a key must be a plain string");
                }
                if (!keys.add(scalar.getValue())) {
                    throw error(key, null, "duplicate key '" + scalar.getValue() + "'");
                }
                refuseUnsupported(entry.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            sequence.getValue().forEach(this::refuseUnsupported);
        }
    }

    private static String shortTag(Tag tag) {
        String value = tag.getValue();
        return value.startsWith(CORE_TAG_PREFIX) ? "!!" + value.substring(CORE_TAG_PREFIX.length()) : value;
    }
}

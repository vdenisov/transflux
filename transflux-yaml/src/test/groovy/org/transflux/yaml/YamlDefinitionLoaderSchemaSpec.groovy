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

package org.transflux.yaml

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.transflux.yaml.LoaderFixtures.Order
import org.transflux.yaml.source.DefinitionResource
import org.transflux.yaml.source.DefinitionSource
import org.yaml.snakeyaml.DumperOptions.ScalarStyle
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import spock.lang.Shared
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

/**
 * Runs every document of the corpus through the JSON Schema and the loader. A document under
 * {@code valid/} both accept; under {@code invalid/} both refuse; under {@code loader-only/} the
 * schema accepts what only the loader can see is wrong. A refused document opens with
 * {@code # error: <message>}, the loader's whole message - or, ending in {@code …}, how it starts,
 * for a message ending in text the module does not own, such as the SpEL parser's; an invalid one adds
 * {@code # schema: <JSON pointer>}, where the schema must report an error. Where a union of
 * definitions reaches that key - an action entry is one of four shapes - {@code # rule: <definition>
 * <JSON pointer>} names the one that must raise it, validated alone against the node at the pointer.
 * A document under an {@code imports/} folder is only reached through an import, so it is not a row
 * of its own. {@code valid/requirements/} holds a copy of every YAML example in {@code requirements.md},
 * at the name the {@code <!-- corpus: name -->} line above the example gives - {@code none} for a
 * sketch no document can hold - wrapped into a document where the example is a fragment, and naming
 * classes stubbed under {@code com.example}.
 */
class YamlDefinitionLoaderSchemaSpec extends Specification {

    static final Path CORPUS = Path.of(YamlDefinitionLoaderSchemaSpec.getResource('/corpus').toURI())

    static final String SYNTHETIC_ROOT = 'root.transflux.yml'

    static final JsonNodeFactory JSON = JsonNodeFactory.instance

    static final String SCHEMA_RESOURCE = '/org/transflux/yaml/transflux-v1.schema.json'

    @Shared
    String schemaText = readSchema()

    @Shared
    String schemaId = new ObjectMapper().readTree(schemaText).get('$id').asText()

    @Shared
    SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
        it.schemas([(schemaId): schemaText])
    }

    @Shared
    Schema schema = registry.getSchema(SchemaLocation.of(schemaId))

    def 'both accept #name'() {
        expect:
        schemaErrors(file) == []
        loaderMessage(file) == null

        where:
        file << documents('valid')
        name = CORPUS.relativize(file).toString()
    }

    def 'both refuse #name'() {
        expect:
        schemaErrors(file).any { at(it, header(file, 'schema')) }
        ruleErrors(file).any { at(it, header(file, 'schema')) }
        pinned(loaderMessage(file), header(file, 'error'))

        where:
        file << documents('invalid')
        name = CORPUS.relativize(file).toString()
    }

    def 'only the loader refuses #name'() {
        expect:
        schemaErrors(file) == []
        pinned(loaderMessage(file), header(file, 'error'))

        where:
        file << documents('loader-only')
        name = CORPUS.relativize(file).toString()
    }

    def 'every YAML example in requirements.md carries a corpus anchor, each name once'() {
        expect:
        examples().findAll { it.anchor == null }*.line == []
        examples()*.anchor.findAll { it != null && it != 'none' }.countBy { it }.findAll { it.value > 1 }*.key == []
    }

    def 'the corpus copy #anchor holds its requirements example'() {
        given:
        Path copy = CORPUS.resolve("valid/requirements/${anchor}.transflux.yml")

        expect:
        Files.exists(copy)
        holds(copy, example)

        where:
        [anchor, example] << examples().findAll { it.anchor != null && it.anchor != 'none' }
            .collect { [it.anchor, it.text] }
    }

    def 'every example copy in the corpus has an anchor in requirements.md'() {
        expect:
        copies().collect { copy ->
            CORPUS.resolve('valid/requirements').relativize(copy).toString().replace('\\', '/') - '.transflux.yml'
        }.findAll { !(it in examples()*.anchor) } == []
    }

    private static List<Path> documents(String folder) {
        return Files.walk(CORPUS.resolve(folder)).withCloseable { paths ->
            paths.filter { it.fileName.toString().endsWith('.yml') }
                .filter { path -> !CORPUS.relativize(path).any { it.toString() == 'imports' } }
                .sorted()
                .toList()
        }
    }

    /**
     * @param file a corpus document
     *
     * @return where each schema error is, as a JSON pointer ending in the key it names, if any
     */
    private List<String> schemaErrors(Path file) {
        return schema.validate(tree(file)).collect { error ->
            error.instanceLocation.toString() + (error.property == null ? '' : '/' + error.property)
        }
    }

    /**
     * @param file an invalid corpus document
     *
     * @return where the definition its {@code # rule:} header names reports errors, validated alone
     *         against the node the header points at; every error the schema reports when it has none
     */
    private List<String> ruleErrors(Path file) {
        String rule = optionalHeader(file, 'rule')
        if (rule == null) {
            return schemaErrors(file)
        }
        def (String definition, String pointer) = rule.split(' ') as List
        return registry.getSchema(SchemaLocation.of("${schemaId}#/\$defs/${definition}"))
            .validate(tree(file).at(pointer))
            .collect { pointer + it.instanceLocation + (it.property == null ? '' : '/' + it.property) }
    }

    private static boolean pinned(String message, String header) {
        return header.endsWith('…') ? message?.startsWith(header[0..-2]) : message == header
    }

    private static boolean at(String location, String pointer) {
        return location == pointer || location.startsWith(pointer + '/')
    }

    /**
     * Loads a document as the root when it declares a state machine, and otherwise as a library
     * imported by a root that declares nothing else. Imports resolve against the document's folder.
     *
     * @param file a corpus document
     *
     * @return the loader's refusal, or {@code null} when it accepted the document
     */
    private static String loaderMessage(Path file) {
        JsonNode document = tree(file)
        String entityType = document.path('stateMachine').path('entityType').asText(Order.name)
        String identifier = document.has('stateMachine') ? file.fileName.toString() : SYNTHETIC_ROOT
        String synthetic = "apiVersion: transflux/v1\nimports:\n  - ${file.fileName}\nstateMachine:\n" +
            "  entityType: ${Order.name}\n"

        assert !Files.exists(file.parent.resolve(SYNTHETIC_ROOT)): "${SYNTHETIC_ROOT} is the synthetic root's name"
        // Reports the identifier alone, as a host's own source might: a resource URL names this machine.
        def source = { String id ->
            Path path = file.parent.resolve(id)
            id == SYNTHETIC_ROOT
                ? Optional.of(new DefinitionResource(id, new ByteArrayInputStream(synthetic.bytes)))
                : Files.exists(path) ? Optional.of(new DefinitionResource(id, Files.newInputStream(path))) : Optional.empty()
        }
        try {
            YamlDefinitionLoader.builder(source as DefinitionSource).build().load(identifier, entity(entityType))
            return null
        } catch (DefinitionLoadException e) {
            return e.message
        }
    }

    /**
     * Reads a document as an editor does, under YAML 1.2's core schema: the validator's own YAML
     * reader follows YAML 1.1, where {@code yes} is a boolean. Anchors, tags and duplicate keys are
     * read through rather than refused, since refusing them is the loader's part.
     *
     * @param file a corpus document
     *
     * @return the document as JSON
     */
    private static JsonNode tree(Path file) {
        LoaderOptions options = new LoaderOptions()
        options.setTagInspector { true }
        return json(new Yaml(options).compose(new StringReader(Files.readString(file))))
    }

    private static JsonNode json(Node node) {
        if (node instanceof MappingNode) {
            def object = JSON.objectNode()
            node.value.each { object.set((it.keyNode as ScalarNode).value, json(it.valueNode)) }
            return object
        }
        if (node instanceof SequenceNode) {
            def array = JSON.arrayNode()
            node.value.each { array.add(json(it)) }
            return array
        }
        ScalarNode scalar = node as ScalarNode
        String text = scalar.value
        if (scalar.scalarStyle != ScalarStyle.PLAIN) {
            return JSON.textNode(text)
        }
        if (text ==~ /|~|null|Null|NULL/) {
            return JSON.nullNode()
        }
        if (text ==~ /true|True|TRUE|false|False|FALSE/) {
            return JSON.booleanNode(text.equalsIgnoreCase('true'))
        }
        if (text ==~ /[-+]?[0-9]+/) {
            return JSON.numberNode(new BigInteger(text))
        }
        if (text ==~ /[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?/) {
            return JSON.numberNode(new BigDecimal(text))
        }
        return JSON.textNode(text)
    }

    private static Class<?> entity(String name) {
        try {
            return Class.forName(name)
        } catch (ClassNotFoundException ignored) {
            // The loader names the missing class itself; any type gets it that far.
            return Object
        }
    }

    /**
     * @param file a refused corpus document
     * @param name the header's name: {@code error} or {@code schema}
     *
     * @return the header's text
     */
    private static String header(Path file, String name) {
        String value = optionalHeader(file, name)
        assert value != null: "${file} has no '# ${name}: ' line"
        return value
    }

    /**
     * @param file a corpus document
     * @param name the header's name
     *
     * @return the header's text, or {@code null} when the document has none
     */
    private static String optionalHeader(Path file, String name) {
        String prefix = "# ${name}: "
        String line = Files.readAllLines(file).takeWhile { it.startsWith('#') }.find { it.startsWith(prefix) }
        return line?.substring(prefix.length())
    }

    /**
     * @return every YAML example in {@code requirements.md}, in order: {@code line} (1-based, of its
     *         fence), {@code anchor} (the name the {@code <!-- corpus: name -->} line above it gives, or
     *         {@code null}) and {@code text}
     */
    private static List<Map<String, Object>> examples() {
        // test-classes/corpus sits four levels below the repository root, in any build that runs this spec.
        Path requirements = CORPUS.parent.parent.parent.parent.resolve('requirements.md')
        assert Files.exists(requirements): "${requirements} not found"
        List<String> lines = Files.readAllLines(requirements)
        List<Map<String, Object>> examples = []
        lines.eachWithIndex { line, index ->
            if (line.startsWith('```yaml')) {
                def anchor = index > 0 ? lines[index - 1] =~ /^<!-- corpus: (\S+) -->$/ : null
                int end = (index + 1..<lines.size()).find { lines[it] == '```' }
                examples << [line  : index + 1,
                             anchor: anchor?.matches() ? anchor.group(1) : null,
                             text  : lines.subList(index + 1, end).join('\n')]
            }
        }
        return examples
    }

    /**
     * @return the corpus's copies of those examples, less the empty libraries standing in for what
     *         they import
     */
    private static List<Path> copies() {
        return documents('valid/requirements').findAll { copy ->
            contentLines(Files.readString(copy)).findAll { !it.startsWith('#') } != ['apiVersion: transflux/v1']
        }
    }

    /**
     * Tells whether a document holds an example, however the example was wrapped: each of its
     * top-level blocks must appear line for line and in order, indentation aside, with the lines a
     * wrapping added in between allowed.
     *
     * @param copy a corpus document
     * @param example a YAML example
     *
     * @return whether the document holds it
     */
    private static boolean holds(Path copy, String example) {
        List<String> document = contentLines(Files.readString(copy))
        List<List<String>> blocks = []
        example.readLines().findAll { !it.isBlank() }.each { line ->
            if (!line.startsWith(' ') || blocks.isEmpty()) {
                blocks << []
            }
            blocks.last() << line.trim()
        }
        return blocks.every { block ->
            int next = 0
            document.each { line ->
                if (next < block.size() && line == block[next]) {
                    next++
                }
            }
            next == block.size()
        }
    }

    private static List<String> contentLines(String text) {
        return text.readLines().findAll { !it.isBlank() }*.trim()
    }

    private static String readSchema() {
        URL resource = YamlDefinitionLoaderSchemaSpec.getResource(SCHEMA_RESOURCE)
        // The pom copies it from docs/schema; a build that skips its resources leaves it out.
        assert resource != null: "${SCHEMA_RESOURCE} is not on the test classpath"
        return resource.getText('UTF-8')
    }
}

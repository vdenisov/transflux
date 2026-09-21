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

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.Preconditions.requireNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.transflux.core.exception.TransfluxValidationException;

/**
 * Reads definitions as files under a root directory, the identifier being a relative path
 * ({@code components/shared.yml}). An identifier that would leave the root - absolute, drive-rooted,
 * or carrying a {@code ..} segment - is refused rather than treated as missing, and so is one whose
 * path breaks the configured {@link SymlinkPolicy}. Answers to {@code file:} in a composite unless
 * given other prefixes. Reports the file's path as its location, and its modification time.
 */
public final class FileSystemDefinitionSource implements DefinitionSource {

    private static final Set<String> DEFAULT_PREFIXES = Set.of("file:");

    private final Path root;
    private final SymlinkPolicy symlinkPolicy;
    private final Set<String> prefixes;

    /**
     * Creates a source under {@code root} following only links that stay under it.
     *
     * @param root the directory identifiers are resolved against
     *
     * @throws TransfluxValidationException if {@code root} is {@code null}
     */
    public FileSystemDefinitionSource(Path root) {
        this(root, SymlinkPolicy.WITHIN_ROOT);
    }

    /**
     * Creates a source under {@code root}.
     *
     * @param root the directory identifiers are resolved against
     * @param symlinkPolicy how links beneath the root are treated
     *
     * @throws TransfluxValidationException if either argument is {@code null}
     */
    public FileSystemDefinitionSource(Path root, SymlinkPolicy symlinkPolicy) {
        this(requireNotNull(root, "root").toAbsolutePath().normalize(),
            requireNotNull(symlinkPolicy, "symlinkPolicy"), DEFAULT_PREFIXES);
    }

    private FileSystemDefinitionSource(Path root, SymlinkPolicy symlinkPolicy, Set<String> prefixes) {
        this.root = root;
        this.symlinkPolicy = symlinkPolicy;
        this.prefixes = prefixes;
    }

    /**
     * Returns a copy of this source answering to the given prefixes in place of its current ones.
     *
     * @param prefixes the prefixes; none leaves the source reachable by an unprefixed identifier only
     *
     * @return the copy
     *
     * @throws TransfluxValidationException if a prefix is {@code null} or blank
     */
    public FileSystemDefinitionSource withPrefixes(String... prefixes) {
        return new FileSystemDefinitionSource(root, symlinkPolicy, CompositeDefinitionSource.checkedPrefixes(prefixes));
    }

    @Override
    public Set<String> prefixes() {
        return prefixes;
    }

    /**
     * Opens the file the identifier names under the root.
     *
     * @param identifier a path relative to the root
     *
     * @return the file, or empty when there is no regular file at that path
     *
     * @throws TransfluxValidationException if the identifier is blank, is not a relative path
     *         staying under the root, or breaks the symlink policy
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    @Override
    public Optional<DefinitionResource> open(String identifier) {
        Path relative = toRelativePath(requireNotBlank(identifier, "identifier"));
        Path resolved = root.resolve(relative).normalize();
        try {
            // ponytail: checked, then opened - a link swapped in between slips past; the filesystem is the host's
            // trust boundary, and closing the window needs a NOFOLLOW_LINKS open per path element
            Path target = switch (symlinkPolicy) {
                case FOLLOW -> resolved;
                case WITHIN_ROOT -> Files.isRegularFile(resolved) ? requireWithinRoot(identifier, resolved) : resolved;
                case REJECT -> requireNoLinks(identifier, relative);
            };
            LinkOption[] linkOptions = symlinkPolicy == SymlinkPolicy.REJECT
                ? new LinkOption[] {LinkOption.NOFOLLOW_LINKS}
                : new LinkOption[0];
            if (!Files.isRegularFile(target, linkOptions)) {
                Loggers.YAML_SOURCE.debug("File definition not found, identifier={}, path={}", identifier, resolved);
                return Optional.empty();
            }
            // before the open, so a failure here cannot leave the stream unclosed
            Instant lastModified = Files.getLastModifiedTime(target, linkOptions).toInstant();
            return Optional.of(new DefinitionResource(identifier, Files.newInputStream(target, linkOptions),
                target.toString(), lastModified, null));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read definition '" + identifier + "' at " + resolved, e);
        }
    }

    private Path toRelativePath(String identifier) {
        Path path;
        try {
            path = root.getFileSystem().getPath(identifier);
        } catch (InvalidPathException e) {
            throw new TransfluxValidationException("Definition identifier '" + identifier + "' is not a valid path", e);
        }
        if (path.isAbsolute() || path.getRoot() != null) {
            throw new TransfluxValidationException(
                "Definition identifier '" + identifier + "' must be a path relative to the source root");
        }
        for (Path element : path) {
            if (element.toString().equals("..")) {
                throw new TransfluxValidationException(
                    "Definition identifier '" + identifier + "' must not contain '..' segments");
            }
        }
        return path;
    }

    private Path requireWithinRoot(String identifier, Path resolved) throws IOException {
        Path real = resolved.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new TransfluxValidationException(
                "Definition identifier '" + identifier + "' resolves through a symbolic link outside the source root");
        }
        return real;
    }

    private Path requireNoLinks(String identifier, Path relative) throws IOException {
        Path current = root;
        Path expectedReal = null;
        for (Path element : relative.normalize()) {
            current = current.resolve(element);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (expectedReal == null) {
                expectedReal = root.toRealPath();
            }
            expectedReal = expectedReal.resolve(element);
            // a Windows junction is no symbolic link to isSymbolicLink, but it still moves the real path
            if (Files.isSymbolicLink(current) || !current.toRealPath().equals(expectedReal)) {
                throw new TransfluxValidationException(
                    "Definition identifier '" + identifier + "' passes through a symbolic link or junction, which this source rejects");
            }
        }
        return current;
    }
}

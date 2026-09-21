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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;

/**
 * One opened definition document: its identifier, its bytes, where it was found, and the optional
 * change metadata a reload watcher would compare. Closing it closes the stream.
 */
public final class DefinitionResource implements AutoCloseable {

    private final String identifier;
    private final InputStream bytes;
    private final String location;
    private final Instant lastModified;
    private final String etag;

    /**
     * Creates a resource with no location and no change metadata.
     *
     * @param identifier the identifier error messages and import deduplication name this document by
     * @param bytes the raw document, owned by this resource from here on
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if either is {@code null},
     *         or the identifier is blank
     */
    public DefinitionResource(String identifier, InputStream bytes) {
        this(identifier, bytes, null, null, null);
    }

    /**
     * Creates a resource.
     *
     * @param identifier the identifier error messages and import deduplication name this document by
     * @param bytes the raw document, owned by this resource from here on
     * @param location where the source found the document, for people reading an error; or {@code null}
     * @param lastModified when the document last changed, or {@code null} if the source cannot tell
     * @param etag an opaque version tag, or {@code null} if the source has none
     *
     * @throws org.transflux.core.exception.TransfluxValidationException if the identifier or the
     *         stream is {@code null}, or the identifier is blank
     */
    public DefinitionResource(String identifier, InputStream bytes, String location, Instant lastModified,
            String etag) {
        this.identifier = requireNotBlank(identifier, "identifier");
        this.bytes = requireNotNull(bytes, "bytes");
        this.location = location;
        this.lastModified = lastModified;
        this.etag = etag;
    }

    /**
     * @return the identifier this document is known by
     */
    public String identifier() {
        return identifier;
    }

    /**
     * @return the raw document; a single-use stream
     */
    public InputStream bytes() {
        return bytes;
    }

    /**
     * @return where the source found the document - a file path, a URL, a table and key - or
     *         {@code null} if the source does not say; unlike the identifier, this names the one
     *         source that answered
     */
    public String location() {
        return location;
    }

    /**
     * @return when the document last changed, or {@code null} if the source cannot tell
     */
    public Instant lastModified() {
        return lastModified;
    }

    /**
     * @return the source's version tag for the document, or {@code null} if it has none
     */
    public String etag() {
        return etag;
    }

    /**
     * Closes the stream.
     *
     * @throws UncheckedIOException if closing the stream fails
     */
    @Override
    public void close() {
        try {
            bytes.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close definition resource '" + identifier + "'", e);
        }
    }

    @Override
    public String toString() {
        return "DefinitionResource[" + identifier + "]";
    }
}

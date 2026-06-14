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

package org.transflux.core.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.transflux.core.Preconditions.requireNotBlank;
import static org.transflux.core.impl.ValidationUtils.warnIfSet;

/**
 * Abstract base for id-bearing definition impls. Adds {@code id}, {@code name}, and
 * {@code description} on top of {@link ConfigurableDefImpl}, along with the standard
 * {@code withName} / {@code withDescription} setters that follow the "last-writer-wins with a
 * warning" pattern.
 *
 * <p>The {@code kind} constant passed through the super-constructor (e.g. {@code "state"},
 * {@code "transition"}) feeds the inherited {@link #defLabel()} so guard error messages embed
 * the appropriate phrase. The {@code idLabel} constant (e.g. {@code "State ID"}) is the label
 * used by the constructor's blank-id validation.
 *
 * <p>The {@code SELF} type parameter gives covariant return on {@code withName} and
 * {@code withDescription} so subclasses do not need to override either method just to narrow
 * the return type. This is framework-internal infrastructure; user code should not invoke it
 * directly.
 *
 * @param <SELF> the concrete subclass type, used for covariant fluent returns
 */
abstract class IdentifiedDefImpl<SELF extends IdentifiedDefImpl<SELF>> extends ConfigurableDefImpl {

    private static final Logger log = LoggerFactory.getLogger(IdentifiedDefImpl.class);

    private final String id;
    private final String kind;

    private String name;
    private String description;

    /**
     * Constructs the base with the supplied id, kind, and id-label. The id is validated against
     * {@code idLabel} via {@link org.transflux.core.Preconditions#requireNotBlank(String, String)}.
     *
     * @param id the def id; must be non-blank
     * @param kind the human-readable kind name (e.g. {@code "state"}), embedded in
     *             {@link #defLabel()}
     * @param idLabel the label used by the blank-id check, e.g. {@code "State ID"}
     */
    protected IdentifiedDefImpl(String id, String kind, String idLabel) {
        requireNotBlank(id, idLabel);
        this.id = id;
        this.kind = kind;
    }

    /**
     * Returns this def's id.
     *
     * @return the id; never {@code null}
     */
    public final String getId() {
        return id;
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    @Override
    protected final String defLabel() {
        return kind + " '" + id + "'";
    }

    @SuppressWarnings("unchecked")
    protected final SELF self() {
        return (SELF) this;
    }

    /**
     * Sets this def's optional name. Last-writer-wins; logs a warning when overwriting a
     * previously-set value.
     *
     * @param name the new name; {@code null} is permitted to clear
     *
     * @return this def, for chaining
     */
    public SELF withName(String name) {
        requireConfigurerActive("withName");
        warnIfSet(this.name, name, "Name", log);
        this.name = name;
        return self();
    }

    /**
     * Sets this def's optional description. Last-writer-wins; logs a warning when overwriting a
     * previously-set value.
     *
     * @param description the new description; {@code null} is permitted to clear
     *
     * @return this def, for chaining
     */
    public SELF withDescription(String description) {
        requireConfigurerActive("withDescription");
        warnIfSet(this.description, description, "Description", log);
        this.description = description;
        return self();
    }
}

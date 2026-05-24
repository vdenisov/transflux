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

import org.transflux.core.operation.OperationDef;

import java.util.Map;
import java.util.Optional;

import static org.transflux.core.Preconditions.requireNotBlank;

/**
 * Sealed base for concrete {@link OperationDef} implementations.
 * <p>
 * Holds the metadata shared by every concrete operation def kind. The mandatory {@code id}
 * is validated in the constructor; {@code name} and {@code description} are optional and can
 * be set through fluent setters that subclasses expose with a covariant return type.
 *
 * <p>The four abstract dispatch methods ({@link #buildBound}, {@link #checkRefs},
 * {@link #bindScope}, {@link #flattenScope}) let the state-machine build pipeline drive both
 * operation kinds uniformly. {@code Simple} variants no-op the scope / refs hooks; only the
 * composite variant carries real bodies.
 *
 * @param <T> the entity type the surrounding state machine manages
 * @param <C> the host-supplied context type carried through transition execution
 */
sealed abstract class OperationDefImpl<T, C> implements OperationDef<T, C>
    permits SimpleOperationDefImpl, CompositeOperationDefImpl {
    private final String id;
    private String name;
    private String description;

    protected OperationDefImpl(String id) {
        requireNotBlank(id, "Operation ID");
        this.id = id;
    }

    @Override
    public final String getId() {
        return id;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public OperationDef<T, C> withName(String name) {
        this.name = name;
        return this;
    }

    @Override
    public OperationDef<T, C> withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Resolves this operation into a runtime {@link BoundOperation}. The {@code stateMachine}
     * argument is consumed by the composite variant to resolve member references; the simple
     * variant ignores it.
     *
     * @param stateMachine the enclosing state machine under construction
     *
     * @return the bound operation
     */
    abstract BoundOperation<T, C> buildBound(StateMachineImpl<T> stateMachine);

    /**
     * Build-time hook: validates this operation's member references (if any) against the
     * supplied scope context and the SM def's component / mapper registries. The simple variant
     * no-ops; the composite variant walks its {@link ActionRef} list.
     *
     * @param scopeContext the call site's enclosing context type
     * @param scopeLabel a human-readable label for the call site (e.g. {@code "transition 't1'"}),
     *                   used in error messages
     * @param smDef the state-machine def whose registries the check consults
     */
    abstract void checkRefs(Class<?> scopeContext, String scopeLabel, StateMachineDefImpl<T> smDef);

    /**
     * Build-time hook: allocates and populates this operation's lexical-scope registry against
     * the enclosing SM. The simple variant no-ops; the composite variant creates a child
     * {@link RegistryImpl} under {@code rootRegistry} and registers its inline members and
     * conditional bound steps into it.
     *
     * @param stateMachine the state machine under construction
     * @param rootRegistry the SM root registry that scopes parent to
     * @param canonical the per-build canonical-payload table enforcing SM-wide id uniqueness
     * @param conditionRegistry the resolved SM-wide condition registry
     */
    abstract void bindScope(StateMachineImpl<T> stateMachine,
                            RegistryImpl<T> rootRegistry,
                            Map<String, Object> canonical,
                            Map<String, BoundCondition<T, ?>> conditionRegistry);

    /**
     * Build-time hook: flattens this operation's scope registry (if any) so runtime
     * {@link Registry#resolve(String)} is a single map lookup. The simple variant no-ops.
     */
    abstract void flattenScope();

    /**
     * Build-time diagnostic hook: returns this operation's id when its local scope registry
     * contains an entry for {@code id} and this operation's id is not {@code excludingId}.
     * Used by {@link ActionRef} resolution to enrich "unknown id" diagnostics when an id
     * exists inline in a sibling composite. The simple variant always returns
     * {@link Optional#empty()}.
     *
     * @param id the id being scanned for
     * @param excludingId the id of the composite originating the search (excluded from the scan)
     *
     * @return this composite's id when the scan matches, otherwise empty
     */
    abstract Optional<String> scanScopeFor(String id, String excludingId);
}

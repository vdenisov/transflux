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

package org.transflux.core.impl

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.transition.Transition
import spock.lang.Specification

class RegistryImplSpec extends Specification {

    static class Entity { }

    static class Ctx { }

    static class NoopStep implements Step<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx context, Transition<Entity, Ctx> transition) { }
    }

    static class NoopOp implements Operation<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx context, Transition<Entity, Ctx> transition) { }
    }

    def 'get returns the locally registered component'() {
        given:
        def registry = new RegistryImpl<Entity>()
        def bound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        registry.register(new Component.Step<>('s1', Ctx, bound))

        expect:
        registry.get('s1').isPresent()
        registry.get('s1').get() instanceof Component.Step
        ((Component.Step) registry.get('s1').get()).bound() == bound
    }

    def 'get returns empty when id is not locally registered, even if the parent has it'() {
        given:
        def parent = new RegistryImpl<Entity>()
        parent.register(new Component.Step<>('s1', Ctx,
            BoundStep.<Entity, Ctx> of('s1', new NoopStep())))
        def child = new RegistryImpl<Entity>(parent)

        expect:
        child.get('s1').isEmpty()
    }

    def 'resolve walks the parent chain when the local registry has no entry'() {
        given:
        def parent = new RegistryImpl<Entity>()
        def parentBound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        parent.register(new Component.Step<>('s1', Ctx, parentBound))
        def child = new RegistryImpl<Entity>(parent)

        expect:
        child.resolve('s1').isPresent()
        ((Component.Step) child.resolve('s1').get()).bound() == parentBound
    }

    def 'resolve returns empty when neither local nor any ancestor holds the id'() {
        given:
        def parent = new RegistryImpl<Entity>()
        def child = new RegistryImpl<Entity>(parent)

        expect:
        child.resolve('missing').isEmpty()
        parent.resolve('missing').isEmpty()
    }

    def 'resolve prefers the innermost (local) entry when the same id is in both child and parent'() {
        given:
        def parent = new RegistryImpl<Entity>()
        def parentBound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        parent.register(new Component.Step<>('s1', Ctx, parentBound))
        def child = new RegistryImpl<Entity>(parent)
        def childBound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        child.register(new Component.Step<>('s1', Ctx, childBound))

        expect:
        ((Component.Step) child.resolve('s1').get()).bound() == childBound
        ((Component.Step) parent.resolve('s1').get()).bound() == parentBound
    }

    def 'ids returns the locally registered ids only, not the parent chain'() {
        given:
        def parent = new RegistryImpl<Entity>()
        parent.register(new Component.Step<>('parent-only', Ctx,
            BoundStep.<Entity, Ctx> of('parent-only', new NoopStep())))
        def child = new RegistryImpl<Entity>(parent)
        child.register(new Component.Step<>('child-only', Ctx,
            BoundStep.<Entity, Ctx> of('child-only', new NoopStep())))

        expect:
        child.ids() == ['child-only'] as Set
        parent.ids() == ['parent-only'] as Set
    }

    def 'parent returns null for a parentless root, and the supplied parent otherwise'() {
        given:
        def parent = new RegistryImpl<Entity>()
        def child = new RegistryImpl<Entity>(parent)

        expect:
        parent.parent() == null
        child.parent() == parent
    }

    def 'register rejects a different component under an id that is already taken'() {
        given:
        def registry = new RegistryImpl<Entity>()
        registry.register(new Component.Step<>('s1', Ctx,
            BoundStep.<Entity, Ctx> of('s1', new NoopStep())))

        when:
        registry.register(new Component.Step<>('s1', Ctx,
            BoundStep.<Entity, Ctx> of('s1', new NoopStep())))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('s1')
    }

    def 'register tolerates re-registering the same component instance under the same id'() {
        given:
        def registry = new RegistryImpl<Entity>()
        def component = new Component.Step<Entity, Ctx>('s1', Ctx,
            BoundStep.<Entity, Ctx> of('s1', new NoopStep()))
        registry.register(component)

        when:
        registry.register(component)

        then:
        notThrown(TransfluxValidationException)
        registry.ids() == ['s1'] as Set
    }

    def 'flatten copies visible ancestor entries into the local map without changing resolve semantics'() {
        given:
        def root = new RegistryImpl<Entity>()
        def rootBound = BoundStep.<Entity, Ctx> of('s-root', new NoopStep())
        root.register(new Component.Step<>('s-root', Ctx, rootBound))

        def child = new RegistryImpl<Entity>(root)
        def childBound = BoundStep.<Entity, Ctx> of('s-child', new NoopStep())
        child.register(new Component.Step<>('s-child', Ctx, childBound))

        when:
        child.flatten()

        then:
        // After flatten the local map holds both entries.
        child.ids() == ['s-child', 's-root'] as Set
        // resolve still returns the same components.
        ((Component.Step) child.resolve('s-root').get()).bound() == rootBound
        ((Component.Step) child.resolve('s-child').get()).bound() == childBound
    }

    def 'flatten leaves the parent unchanged and keeps parent() in place'() {
        given:
        def root = new RegistryImpl<Entity>()
        root.register(new Component.Step<>('s-root', Ctx,
            BoundStep.<Entity, Ctx> of('s-root', new NoopStep())))
        def child = new RegistryImpl<Entity>(root)

        when:
        child.flatten()

        then:
        root.ids() == ['s-root'] as Set
        child.parent() == root
    }

    def 'flatten does not overwrite a local entry with an ancestor entry that shares its id'() {
        given:
        def root = new RegistryImpl<Entity>()
        def rootBound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        root.register(new Component.Step<>('s1', Ctx, rootBound))

        def child = new RegistryImpl<Entity>(root)
        def childBound = BoundStep.<Entity, Ctx> of('s1', new NoopStep())
        child.register(new Component.Step<>('s1', Ctx, childBound))

        when:
        child.flatten()

        then:
        ((Component.Step) child.resolve('s1').get()).bound() == childBound
    }

    def 'register rejects when the same id is already taken by a different component kind'() {
        given:
        def registry = new RegistryImpl<Entity>()
        registry.register(new Component.Step<>('id-x', Ctx,
            BoundStep.<Entity, Ctx> of('id-x', new NoopStep())))

        when:
        registry.register(new Component.Operation<>('id-x', Ctx,
            BoundOperation.<Entity, Ctx> of('id-x', new NoopOp())))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('id-x')
    }
}

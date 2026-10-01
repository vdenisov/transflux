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

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.ActionListener
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification

import java.util.function.Consumer

/**
 * A listener registered once and attached by id: what a reference resolves against, what the build
 * rejects, and that one registration stays one listener however many hooks attach it.
 */
class StateMachineDefImplListenerRegistrationSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx {}

    static class OtherCtx {}

    // One listener attached twice at one hook, per kind of owner.
    static final Closure TRANSITION_TWICE = { d -> d.transition('t', 's1', 's2', { t -> t.onStart('audit').onStart('audit') }) }
    static final Closure TRANSITION_OWN_TWICE = { d ->
        d.transition('t', 's1', 's2', { t -> t.onStart('own', { en, c, x -> } as TransitionListener).onStart('own') })
    }
    static final Closure STATE_TWICE = { d -> d.state('s3', { s -> s.onEntry('seen').onEntry('seen') }) }
    static final Closure ACTION_TWICE = { d ->
        d.transition('t', 's1', 's2', { t ->
            t.step('a', { a -> a.using({ en, c, x -> } as Action).onError('act').onError('act') }) })
    }
    static final Closure GLOBAL_TWICE = { d ->
        d.onAnyTransitionStart('g', { en, c, x -> } as TransitionListener).onAnyTransitionStart('g')
    }

    // A reference naming a listener declared in place in another category.
    static final Closure GLOBAL_OTHER_CATEGORY = { d ->
        d.onAnyTransitionStart('x', { en, c, x -> } as TransitionListener).onAnyStateEntry('x')
    }
    static final Closure OWNER_OTHER_CATEGORY = { d -> d
        .state('s3', { s -> s.onEntry('y', { en, c, ch -> } as StateListener) })
        .transition('t', 's1', 's2', { t -> t.onStart('y') })
    }
    static final Closure ACTION_OTHER_CATEGORY = { d -> d
        .transition('u', 's1', 's2', { t ->
            t.step('a', { a -> a.using({ en, c, x -> } as Action).onStart('z', { en, c, x -> } as ActionListener) }) })
        .transition('t', 's1', 's2', { t -> t.onStart('z') })
    }

    def 'one registered listener serves several hooks and stays one listener'() {
        given:
        def seen = []
        def sm = build({ d -> d
            .transitionListener('audit', { l -> l.using({ e, c, x -> seen << x.phase().toString() } as TransitionListener) })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .onStart('audit')
                .onComplete('audit') })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then: 'attaching claims nothing, so one id covers both hooks'
        seen == ['START', 'COMPLETE']
    }

    def 'a reference reaches a listener the same owner declared at another hook'() {
        given:
        def seen = []
        def sm = build({ d -> d
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .onStart('own', { l -> l.using({ e, c, x -> seen << x.phase().toString() } as TransitionListener) })
                .onComplete('own') })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        seen == ['START', 'COMPLETE']
    }

    def 'a reference may precede the declaration it names'() {
        given: 'references resolve at build, so a hook may name one declared further down'
        def seen = []
        def sm = build({ d -> d
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .onStart('later')
                .onComplete('later', { l -> l.using({ e, c, x -> seen << x.phase().toString() } as TransitionListener) }) })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        seen == ['START', 'COMPLETE']
    }

    def 'attaching an unregistered id fails the build'() {
        when:
        build({ d -> d
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.onStart('ghost') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("attaches listener 'ghost'")
        e.message.contains('not a registered transition listener')
    }

    def "attaching another owner's declaration says so rather than calling it unknown"() {
        when:
        build({ d -> d
            .state('s1', { s -> s
                .onEntry('local', { l -> l.using({ e, c, ch -> } as StateListener) }) })
            .transition('t', 's1', 's2', { t -> })
            .state('s2', { s -> s.onEntry('local') }) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("is declared on state 's1'")
        e.message.contains('register it')
    }

    def 'a reference reaching another category gets its own message'() {
        when:
        build({ d -> d
            .actionListener('audit', { l -> l.using({ e, c, x -> } as ActionListener) })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.onStart('audit') })
            .state('s2') })

        then: 'it exists, just not in the category this hook reaches'
        def e = thrown(TransfluxValidationException)
        e.message == "transition 't' attaches listener 'audit', which is registered as an action listener; a hook only reaches listeners of its own category"
    }

    def "a registration's context is checked against every owner attaching it"() {
        when:
        build({ d -> d
            .transitionListener('audit', Ctx, { l -> l.using({ e, c, x -> } as TransitionListener) })
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t.onStart('audit') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("transition 't'")
    }

    def 'an untyped registration attaches to an owner carrying any context'() {
        given: 'registering without a context class means Object, which accepts anything'
        def seen = []
        def sm = build({ d -> d
            .transitionListener('audit', { l -> l.using({ e, c, x -> seen << x.phase().toString() } as TransitionListener) })
            .state('s1')
            .transition('t', 's1', 's2', Ctx, { t -> t.onStart('audit') })
            .state('s2') })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new Ctx())

        then:
        seen == ['START']
    }

    def 'a typed registration cannot be attached state-machine-wide'() {
        when:
        build({ d -> d
            .transitionListener('audit', Ctx, { l -> l.using({ e, c, x -> } as TransitionListener) })
            .onAnyTransitionStart('audit')
            .state('s1') })

        then: 'a hook spanning every transition cannot promise one context'
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains('the state machine')
    }

    def 'a registration claims its id in the one listener namespace'() {
        when:
        build({ d -> d
            .transitionListener('dup', { l -> l.using({ e, c, x -> } as TransitionListener) })
            .actionListener('dup', { l -> l.using({ e, c, x -> } as ActionListener) })
            .state('s1') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Listener ID 'dup' is already registered")
    }

    def 'a registration nothing attaches is legal'() {
        expect: 'a component library may register listeners a definition does not use'
        build({ d -> d
            .stateListener('unused', { l -> l.using({ e, c, ch -> } as StateListener) })
            .state('s1')
            .transition('t', 's1', 's2', { t -> })
            .state('s2') }) != null
    }

    def 'one listener attached to an owner and globally is delivered twice, and a disable filters only the global'() {
        given:
        def seen = []
        def listener = { e, c, x -> seen << x.phase().toString() } as TransitionListener
        def sm = build({ d -> d
            .transitionListener('audit', { l -> l.using(listener) })
            .onAnyTransitionStart('audit')
            .state('s1')
            .transition('both', 's1', 's2', { t -> t.onStart('audit') })
            .transition('disabled', 's1', 's3', { t -> t
                .onStart('audit')
                .disableGlobalListener('audit') })
            .state('s2')
            .state('s3') })

        when: 'the transition that disables nothing gets it from both places'
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        seen == ['START', 'START']

        when: 'the one that disables it keeps its own attachment'
        seen.clear()
        sm.entity(new Entity('s1')).transitionTo('s3')

        then:
        seen == ['START']
    }

    def 'a listener registered inside forContext carries that scope context: #form'() {
        when:
        build({ d -> d
            .forContext(Ctx, register)
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t.onStart('scoped') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')

        where:
        form         | register
        'configurer' | { scope -> scope.transitionListener('scoped', { l -> l.using({ en, c, x -> } as TransitionListener) }) }
        'instance'   | { scope -> scope.transitionListener('scoped', { en, c, x -> } as TransitionListener) }
    }

    def "an inline action's inherited context is what its listener attachment is checked against"() {
        when: 'the step declares no context, so it runs against the one the transition carries'
        build({ d -> d
            .actionListener('audit', Ctx, { l -> l.using({ e, c, x -> } as ActionListener) })
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t
                .step('tracked', { st -> st
                    .using({ e, c, v -> } as Action)
                    .onStart('audit') }) })
            .state('s2') })

        then: 'the Object sentinel the def reports is not the context it runs against'
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("step 'tracked'")
        e.message.contains(OtherCtx.name)
    }

    def 'a registration whose configurer throws leaves its id free for the retry'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        def builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)

        when: 'the first attempt fails inside the configurer'
        builder.stateListener('audit', { l -> throw new IllegalStateException('typo') })

        then:
        thrown(IllegalStateException)

        when: 'the corrected retry uses the same id'
        builder.stateListener('audit', { l -> l.using({ e, c, ch -> } as StateListener) })

        then: 'nothing was claimed, so it is free'
        noExceptionThrown()
    }

    def 'a registration that declares no listener is rejected where it is registered'() {
        when: 'the configurer sets metadata but never calls using(...)'
        build({ d -> d
            .stateListener('audit', { l -> l.withName('Audit') })
            .state('s1') })

        then: 'reported here rather than when something eventually attaches it - or never'
        def e = thrown(TransfluxValidationException)
        e.message.contains('declares no listener')
        e.message.contains('audit')
    }

    def "a global hook naming an owner's inline listener says where it was declared"() {
        when:
        build({ d -> d
            .state('s1', { s -> s.onEntry('local', { l -> l.using({ e, c, ch -> } as StateListener) }) })
            .onAnyStateEntry('local') })

        then: 'the same message an owner-to-owner reference gets, not "not registered"'
        def e = thrown(TransfluxValidationException)
        e.message.contains("is declared on state 's1'")
        e.message.contains('register it')
    }

    def "an owner naming a listener declared at a global hook says where it was declared"() {
        when:
        build({ d -> d
            .onAnyStateEntry('glob', { l -> l.using({ e, c, ch -> } as StateListener) })
            .state('s1', { s -> s.onExit('glob') }) })

        then: 'the eight onAny hooks are an owner too, so this is not an unknown id'
        def e = thrown(TransfluxValidationException)
        e.message.contains('is declared on the state machine')
        e.message.contains('register it')
    }

    def 'a reference reaching a listener declared in place in another category says so: #scenario'() {
        when:
        build({ d -> declare.call(d.state('s1').state('s2')) })

        then: 'registering it would not help, so the advice is about the category, not visibility'
        def e = thrown(TransfluxValidationException)
        e.message == message

        where:
        scenario                         | declare                 || message
        'between two global hooks'       | GLOBAL_OTHER_CATEGORY   || "the state machine attaches listener 'x', which is a transition listener declared on the state machine; a hook only reaches listeners of its own category"
        'from a transition to a state'   | OWNER_OTHER_CATEGORY    || "transition 't' attaches listener 'y', which is a state listener declared on state 's3'; a hook only reaches listeners of its own category"
        'from a transition to an action' | ACTION_OTHER_CATEGORY   || "transition 't' attaches listener 'z', which is an action listener declared on step 'a'; a hook only reaches listeners of its own category"
    }

    def 'the untyped instance registration takes an Object-context listener, so nothing escapes the check'() {
        given: 'a listener written against a context, registered through the typed form'
        def sm = build({ d -> d
            .transitionListener('audit', Ctx, { l -> l.using({ e, c, x -> } as TransitionListener) })
            .state('s1')
            .transition('t', 's1', 's2', Ctx, { t -> t.onStart('audit') })
            .state('s2') })

        expect: 'the typed form is the only way in, and it is checked against every attachment'
        sm != null

        and: 'the untyped instance form is declared against Object rather than a wildcard'
        StateMachineDef.getMethod('transitionListener', String, TransitionListener)
                       .genericParameterTypes[1].typeName.endsWith('<? super T, java.lang.Object>')
        StateMachineDef.getMethod('actionListener', String, ActionListener)
                       .genericParameterTypes[1].typeName.endsWith('<? super T, java.lang.Object>')
    }

    def 'one listener attached twice at one hook is refused at #owner, since attaching is not additive'() {
        when:
        build({ d -> attach.call(d
            .transitionListener('audit', { l -> l.using({ en, c, x -> } as TransitionListener) })
            .stateListener('seen', { l -> l.using({ en, c, x -> } as StateListener) })
            .actionListener('act', { l -> l.using({ en, c, x -> } as ActionListener) })
            .state('s1')
            .state('s2')) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message == message

        where:
        owner                         | attach                   || message
        'a transition, by reference'  | TRANSITION_TWICE         || "transition 't' attaches listener 'audit' more than once at onStart; attaching is not additive"
        'a transition, by its own id' | TRANSITION_OWN_TWICE     || "transition 't' attaches listener 'own' more than once at onStart; attaching is not additive"
        'a state'                     | STATE_TWICE              || "state 's3' attaches listener 'seen' more than once at onEntry; attaching is not additive"
        'an action'                   | ACTION_TWICE             || "step 'a' attaches listener 'act' more than once at onError; attaching is not additive"
        'a state-machine-wide hook'   | GLOBAL_TWICE             || "the state machine attaches listener 'g' more than once at onAnyTransitionStart; attaching is not additive"
    }

    def "a transition's in-place listener ids stay free when its configurer throws"() {
        given:
        def smd = new StateMachineDefImpl<Entity>()

        when:
        smd.transition('t', 's1', 's2', { t ->
            t.onStart('audit', { en, c, x -> } as TransitionListener)
            throw new IllegalStateException('bad configurer')
        })

        then:
        thrown(IllegalStateException)

        when:
        smd.onAnyTransitionStart('audit', { en, c, x -> } as TransitionListener)

        then:
        noExceptionThrown()
    }

    def 'one listener at two hooks of one owner is still legal'() {
        when:
        build({ d -> d
            .transitionListener('audit', { l -> l.using({ e, c, x -> } as TransitionListener) })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.onStart('audit').onComplete('audit').onError('audit') })
            .state('s2') })

        then:
        noExceptionThrown()
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }
}

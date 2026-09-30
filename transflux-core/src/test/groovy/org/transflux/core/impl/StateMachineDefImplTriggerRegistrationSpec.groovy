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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.function.Consumer

/**
 * A trigger registered once and attached to several transitions: what the registration claims, what
 * an attachment checks, and how firing picks between attachments.
 */
class StateMachineDefImplTriggerRegistrationSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx {}

    static class OtherCtx {}

    def 'one registered manual trigger serves two transitions and is listed once'() {
        given:
        def sm = build({ d -> d
            .manualTrigger('cancel', { t -> t.withName('Cancel') })
            .state('active')
            .transition('cancel-active', 'active', 'cancelled', { t -> t.addTrigger('cancel') })
            .state('suspended')
            .transition('cancel-suspended', 'suspended', 'cancelled', { t -> t.addTrigger('cancel') })
            .state('cancelled') })

        expect: 'one trigger, reporting both transitions'
        sm.getTriggers().size() == 1
        sm.getTrigger('cancel').name == 'Cancel'
        sm.getTrigger('cancel').transitionIds == ['cancel-active', 'cancel-suspended']
    }

    def 'firing a shared manual trigger picks the attachment leaving the current state'() {
        given:
        def sm = build({ d -> d
            .manualTrigger('cancel', { t -> })
            .state('active')
            .transition('cancel-active', 'active', 'cancelled', { t -> t.addTrigger('cancel') })
            .state('suspended')
            .transition('cancel-suspended', 'suspended', 'cancelled', { t -> t.addTrigger('cancel') })
            .state('cancelled') })

        when: 'the same id is fired from either state'
        def fromActive = sm.entity(new Entity('active')).fire('cancel')
        def fromSuspended = sm.entity(new Entity('suspended')).fire('cancel')

        then: 'the host never had to know which state the entity was in'
        fromActive.transitionId == 'cancel-active'
        fromSuspended.transitionId == 'cancel-suspended'
    }

    def 'firing from a state the trigger does not leave names the states it does'() {
        given:
        def sm = build({ d -> d
            .manualTrigger('cancel', { t -> })
            .state('active')
            .transition('cancel-active', 'active', 'cancelled', { t -> t.addTrigger('cancel') })
            .state('cancelled') })

        when:
        sm.entity(new Entity('cancelled')).fire('cancel')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("state 'cancelled'")
        e.message.contains('only [active]')
    }

    def 'an attachment may precede the registration it names'() {
        given: 'attachment is checked at build, so declaration order is free'
        def sm = build({ d -> d
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.addTrigger('later') })
            .state('s2')
            .manualTrigger('later', { t -> }) })

        expect:
        sm.getTrigger('later').transitionIds == ['t']
    }

    def 'a registration nothing attaches is still a trigger, attached to nothing'() {
        given: 'a library may register triggers a definition does not use'
        def sm = build({ d -> d
            .manualTrigger('unused', { t -> })
            .state('s1')
            .transition('t', 's1', 's2', { t -> })
            .state('s2') })

        expect:
        sm.getTrigger('unused').transitionIds == []
    }

    def 'attaching an unregistered id fails the build'() {
        when:
        build({ d -> d
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.addTrigger('ghost') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("attaches trigger 'ghost'")
        e.message.contains('not registered')
    }

    def "attaching another transition's inline trigger says so rather than calling it unknown"() {
        when:
        build({ d -> d
            .state('s1')
            .transition('first', 's1', 's2', { t -> t.addManualTrigger('local') })
            .state('s2')
            .transition('second', 's2', 's3', { t -> t.addTrigger('local') })
            .state('s3') })

        then: 'it exists - it is just visible to the transition that declared it'
        def e = thrown(TransfluxValidationException)
        e.message.contains("declared inline on transition 'first'")
        e.message.contains('register it')
    }

    def 'one manual trigger on two transitions leaving one state is ambiguous and fails the build'() {
        when:
        build({ d -> d
            .manualTrigger('go', { t -> })
            .state('s1')
            .transition('a', 's1', 's2', { t -> t.addTrigger('go') })
            .transition('b', 's1', 's3', { t -> t.addTrigger('go') })
            .state('s2')
            .state('s3') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Manual trigger 'go'")
        e.message.contains("leave state 's1'")
        e.message.contains('dispatch could not choose')
    }

    def '#kind attached to two transitions leaving one state is ambiguous whatever its kind'() {
        when:
        build({ d -> d
            .state('s1')
            .transition('a', 's1', 's2', { t -> t.addTrigger('go') })
            .transition('b', 's1', 's3', { t -> t.addTrigger('go') })
            .state('s2')
            .state('s3')
            .with(register) })

        then: 'the filter or gate is one object, so the second attachment could never fire'
        def e = thrown(TransfluxValidationException)
        e.message.contains("transitions 'a' and 'b'")
        e.message.contains("leave state 's1'")

        where:
        kind     | register
        'manual' | { d -> d.manualTrigger('go', { t -> }) }
        'event'  | { d -> d.eventTrigger('go', { t -> t.onEvent('E') }) }
        'data'   | { d -> d.dataTrigger('go', { t -> t.conditionExpression('state != null') }) }
    }

    def 'a registration typed to a context is checked against every transition attaching it'() {
        when:
        build({ d -> d
            .manualTrigger('typed', Ctx, { t -> })
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t.addTrigger('typed') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("transition 't'")
        e.message.contains('manual trigger')
    }

    def 'an untyped registration attaches to a transition carrying any context'() {
        given: 'registering without a context class means Object, which accepts anything'
        def sm = build({ d -> d
            .manualTrigger('anywhere', { t -> })
            .state('s1')
            .transition('t', 's1', 's2', Ctx, { t -> t.addTrigger('anywhere') })
            .state('s2') })

        expect:
        sm.getTrigger('anywhere').transitionIds == ['t']
    }

    def 'a trigger registered inside forContext carries that scope context'() {
        when:
        build({ d -> d
            .forContext(Ctx, { scope -> scope.manualTrigger('scoped', { t -> }) })
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t.addTrigger('scoped') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
    }

    def 'a registration cannot reuse an id another trigger already claimed'() {
        when:
        build({ d -> d
            .manualTrigger('dup', { t -> })
            .eventTrigger('dup', { t -> t.onEvent('E') })
            .state('s1') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Trigger id 'dup' is already registered")
    }

    def 'a registration cannot reuse an id its own configurer registered meanwhile'() {
        given:
        def smd = new StateMachineDefImpl<Object>()

        when:
        smd.manualTrigger('dup', { t -> smd.manualTrigger('dup', { inner -> }) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Trigger id 'dup' is already registered as a manual trigger 'dup'; ids are unique across" +
            " this state machine's triggers"
    }

    def 'an inline trigger cannot reuse a registered id'() {
        when:
        build({ d -> d
            .manualTrigger('clash', { t -> })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.addManualTrigger('clash') })
            .state('s2') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Trigger id 'clash' is registered on the state machine and declared on transition 't'")
    }

    def 'a shared event trigger fires from whichever state the entity is in'() {
        given:
        def sm = build({ d -> d
            .eventTrigger('paid', { t -> t.onEvent('PAID') })
            .state('draft')
            .transition('from-draft', 'draft', 'done', { t -> t.addTrigger('paid') })
            .state('held')
            .transition('from-held', 'held', 'done', { t -> t.addTrigger('paid') })
            .state('done') })

        when:
        def fromDraft = sm.entity(new Entity('draft')).processEvent('PAID', null)
        def fromHeld = sm.entity(new Entity('held')).processEvent('PAID', null)

        then:
        fromDraft.result().get().transitionId == 'from-draft'
        fromHeld.result().get().transitionId == 'from-held'

        and: 'still one trigger in the catalog'
        sm.getTriggers().size() == 1
        sm.getTrigger('paid').transitionIds == ['from-draft', 'from-held']
    }

    def "a transition's own declarations are scanned before the triggers it attaches"() {
        given:
        def sm = build({ d -> d
            .eventTrigger('attached', { t -> t.onEvent('E') })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t
                .addTrigger('attached')
                .addEventTrigger('inline', { et -> et.onEvent('E') }) })
            .state('s2') })

        when: 'both match the same event on the same transition'
        def fired = sm.entity(new Entity('s1')).processEvent('E', null)

        then: 'the inline one wins, whichever order the two were written in'
        fired.firedTriggerId() == 'inline'
    }

    def "a registration's own condition references are checked against its declared context"() {
        when: 'the condition is typed to one context, the trigger to another'
        build({ d -> d
            .condition('vip', Ctx, { e, c -> true })
            .manualTrigger('cancel', OtherCtx, { t -> t.preCondition('vip') })
            .state('s1')
            .transition('t', 's1', 's2', OtherCtx, { t -> t.addTrigger('cancel') })
            .state('s2') })

        then: 'no transition walk reaches a registration, so it needs a check of its own'
        def e = thrown(TransfluxValidationException)
        e.message.contains('Context type mismatch')
        e.message.contains("manual trigger 'cancel'")
    }

    def "a registration's inline condition id is claimed like any other"() {
        when:
        build({ d -> d
            .condition('gate', { e -> true })
            .dataTrigger('swept', { t -> t.condition('gate', { e, c -> false }) })
            .state('s1') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('gate')
        e.message.contains('already registered')
    }

    def 'attaching the same trigger twice to one transition is rejected'() {
        when:
        build({ d -> d
            .eventTrigger('paid', { t -> t.onEvent('PAID') })
            .state('s1')
            .transition('t', 's1', 's2', { t -> t.addTrigger('paid').addTrigger('paid') })
            .state('s2') })

        then: 'it would double-bind the trigger and report its transition twice'
        def e = thrown(TransfluxValidationException)
        e.message.contains("attaches trigger 'paid' more than once")
    }

    def 'scan order stays per transition when a registration is attached from two states'() {
        given: 'an earlier transition attaches the registration, a later one declares its own too'
        def sm = build({ d -> d
            .eventTrigger('shared', { t -> t.onEvent('E') })
            .state('s1')
            .transition('first', 's1', 's2', { t -> t.addTrigger('shared') })
            .state('s2')
            .transition('second', 's2', 's3', { t -> t
                .addEventTrigger('own', { et -> et.onEvent('E') })
                .addTrigger('shared') })
            .state('s3') })

        when: 'the entity is in the state the later transition leaves'
        def fired = sm.entity(new Entity('s2')).processEvent('E', null)

        then: 'its own declaration is scanned first, not the registration an earlier one attached'
        fired.firedTriggerId() == 'own'
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

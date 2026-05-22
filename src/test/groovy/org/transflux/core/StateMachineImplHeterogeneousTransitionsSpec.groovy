/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *
 */

package org.transflux.core

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import spock.lang.Specification

/**
 * Acceptance test for the per-transition context refactor. One StateMachine<Offer> drives three
 * transitions with three different context types, mirroring the user-story example: drafting an
 * offer (requires opening + proposal), submitting it (requires contact details), withdrawing it
 * (requires withdraw reason/code).
 */
class StateMachineImplHeterogeneousTransitionsSpec extends Specification {

    static class Offer {
        String state
        String openingId
        String proposalText
        String contactEmail
        String withdrawCode
        Offer(String state) { this.state = state }
    }

    static class DraftCtx {
        String openingId
        String proposalText
        DraftCtx(String openingId, String proposalText) {
            this.openingId = openingId
            this.proposalText = proposalText
        }
    }

    static class SubmitCtx {
        String contactEmail
        SubmitCtx(String contactEmail) { this.contactEmail = contactEmail }
    }

    static class WithdrawCtx {
        String code
        WithdrawCtx(String code) { this.code = code }
    }

    private StateMachine<Offer> buildSm() {
        StateMachineDef<Offer> smd = Transflux.<Offer> defineStateMachine()
            .forEntityType(Offer)
            .withStateResolver({ o -> o.state } as StateResolver<Offer>)
            .withStateApplier({ o, s -> o.state = s })

        smd.state('new', { s -> s.transitionsTo('drafted', 'draft', DraftCtx, { t ->
            t.simpleOperation('op-draft', { Offer o, DraftCtx c, tx ->
                o.openingId = c.openingId
                o.proposalText = c.proposalText
            })
        }) })
        smd.state('drafted', { s -> s
            .transitionsTo('submitted', 'submit', SubmitCtx, { t ->
                t.simpleOperation('op-submit', { Offer o, SubmitCtx c, tx ->
                    o.contactEmail = c.contactEmail
                })
            })
            .transitionsTo('withdrawn', 'withdraw', WithdrawCtx, { t ->
                t.simpleOperation('op-withdraw', { Offer o, WithdrawCtx c, tx ->
                    o.withdrawCode = c.code
                })
            }) })
        smd.state('submitted', {})
        smd.state('withdrawn', {})

        return smd.build()
    }

    def 'three transitions on one SM each accept their own context type'() {
        given:
        def sm = buildSm()

        when: 'draft transition with DraftCtx'
        def offer = new Offer('new')
        def r1 = sm.entity(offer).transitionTo('drafted', new DraftCtx('op-7', 'hello'))

        then:
        r1.success
        offer.state == 'drafted'
        offer.openingId == 'op-7'
        offer.proposalText == 'hello'

        when: 'submit transition with SubmitCtx'
        def r2 = sm.entity(offer).transitionTo('submitted', new SubmitCtx('alice@example.com'))

        then:
        r2.success
        offer.state == 'submitted'
        offer.contactEmail == 'alice@example.com'
    }

    def 'withdrawing from drafted with WithdrawCtx works'() {
        given:
        def sm = buildSm()
        def offer = new Offer('new')
        sm.entity(offer).transitionTo('drafted', new DraftCtx('op-1', 'p'))

        when:
        def r = sm.entity(offer).transitionTo('withdrawn', new WithdrawCtx('WC-1'))

        then:
        r.success
        offer.state == 'withdrawn'
        offer.withdrawCode == 'WC-1'
    }

    def 'firing draft with the wrong context type is rejected at the dispatch boundary'() {
        given:
        def sm = buildSm()
        def offer = new Offer('new')

        when:
        sm.entity(offer).transitionTo('drafted', new SubmitCtx('x'))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('DraftCtx')
        e.message.contains('SubmitCtx')
    }

    def 'firing submit with the wrong context type is rejected at the dispatch boundary'() {
        given:
        def sm = buildSm()
        def offer = new Offer('new')
        sm.entity(offer).transitionTo('drafted', new DraftCtx('op-1', 'p'))

        when:
        sm.entity(offer).transitionTo('submitted', new WithdrawCtx('x'))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('SubmitCtx')
        e.message.contains('WithdrawCtx')
    }
}

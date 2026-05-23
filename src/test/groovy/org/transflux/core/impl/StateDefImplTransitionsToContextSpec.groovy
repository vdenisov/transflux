/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *
 */

package org.transflux.core.impl

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class StateDefImplTransitionsToContextSpec extends Specification {

    static class Entity {
        String state
        Entity(String state) { this.state = state }
    }

    static class CtxA { }
    static class CtxB { }

    def 'transitionsTo(target, id, configurer) defaults the transition context to Object'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})

        when:
        def td = smd.getTransition('t')

        then:
        td.getContextType() == Object
    }

    def 'transitionsTo(target, id, Class, configurer) pre-binds the transition context'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxA, {}) })
            .state('s2', {})

        when:
        def td = smd.getTransition('t')

        then:
        td.getContextType() == CtxA
    }

    def 'pre-bound transition rejects fire calls with a wrong context type'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxA, {}) })
            .state('s2', {})
        def sm = smd.build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxB())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('CtxA')
        e.message.contains('CtxB')
    }

    def 'pre-bound transition accepts fire calls with the matching context type'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', CtxA, {}) })
            .state('s2', {})
        def sm = smd.build()

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then:
        result.success
    }

    def 'transitionsTo with null contextType raises validation error'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)

        when:
        smd.state('s1', { s -> s.transitionsTo('s2', 't', (Class) null, {}) })

        then:
        thrown(TransfluxValidationException)
    }
}

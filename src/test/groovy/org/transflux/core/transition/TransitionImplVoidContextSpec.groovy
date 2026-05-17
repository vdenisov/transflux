/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *
 */

package org.transflux.core.transition

import org.transflux.core.StateMachineDefImpl
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import spock.lang.Specification

class TransitionImplVoidContextSpec extends Specification {

    static class Entity {
        String state
        Entity(String state) { this.state = state }
    }

    static class CtxA { }

    def 'firing a Void-context transition with null context succeeds'() {
        given:
        def sm = baseDef(Void).build()

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', (Object) null)

        then:
        result.success
    }

    def 'firing a Void-context transition with a non-null context is rejected'() {
        given:
        def sm = baseDef(Void).build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Void')
        e.message.contains("'t'")
    }

    def 'pre-binding Void via transitionsTo overload yields the same Void semantics'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't', Void)
            .state('s2')
        def sm = smd.build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new CtxA())

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('Void')
    }

    private static StateMachineDefImpl<Entity> baseDef(Class<?> ctx) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't')
            .state('s2')
        smd.getTransition('t').usingContext(ctx)
        return smd
    }
}

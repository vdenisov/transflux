/*
 *
 *  * Copyright 2025 Victor Denisov
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *
 */

package org.transflux.core

import org.transflux.core.condition.Condition
import org.transflux.core.operation.Step
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

class StateMachineDefImplExplicitContextOverloadsSpec extends Specification {

    static class Entity {
        String state
        Entity(String state) { this.state = state }
    }

    static class CtxA { }
    static class CtxB { }

    static class CtxAStep implements Step<Entity, CtxA> {
        @Override
        void execute(Entity entity, CtxA context, Transition<Entity, CtxA> transition) { }
    }

    static class CtxACondition implements Condition<Entity, CtxA> {
        @Override
        boolean test(Entity entity, CtxA context, Transition<Entity, CtxA> transition) { true }
    }

    def 'step(id, Class<C>, Step) tags componentContextTypes identically to useContext scope'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('viaExplicit', CtxA, new CtxAStep())
            .useContext(CtxA, { scope -> scope.step('viaScope', new CtxAStep()) })

        expect:
        smd.getComponentContextType('viaExplicit') == CtxA
        smd.getComponentContextType('viaScope') == CtxA
    }

    def 'condition(id, Class<C>, Condition) tags componentContextTypes identically to useContext scope'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .condition('viaExplicit', CtxA, new CtxACondition())
            .useContext(CtxA, { scope -> scope.condition('viaScope', new CtxACondition()) })

        expect:
        smd.getComponentContextType('viaExplicit') == CtxA
        smd.getComponentContextType('viaScope') == CtxA
    }

    def 'compositeOperation(id, Class<C>, configurer) tags componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .compositeOperation('comp', CtxA, { c -> c.step('s', new CtxAStep()) })

        expect:
        smd.getComponentContextType('comp') == CtxA
    }

    def 'conditionPredicate and conditionExpression with Class<C> tag componentContextTypes'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .conditionPredicate('predCond', CtxA, { e -> true })
            .conditionExpression('exprCond', CtxA, 'true')

        expect:
        smd.getComponentContextType('predCond') == CtxA
        smd.getComponentContextType('exprCond') == CtxA
    }

    def 'untyped step/condition registrations leave componentContextTypes unset'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('plain', new CtxAStep())
            .condition('plainCond', new CtxACondition())

        expect:
        smd.getComponentContextType('plain') == null
        smd.getComponentContextType('plainCond') == null
    }

    def 'mixing explicit Class<C> with mismatched useContext on same id is rejected'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .step('mixed', CtxA, new CtxAStep())

        when:
        smd.useContext(CtxB, { scope -> scope.step('mixed', new CtxAStep() as Step<Entity, CtxB>) })

        then:
        thrown(org.transflux.core.exception.TransfluxValidationException)
    }
}

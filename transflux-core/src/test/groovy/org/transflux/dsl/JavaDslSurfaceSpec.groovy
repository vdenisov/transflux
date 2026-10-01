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

package org.transflux.dsl

import spock.lang.Specification

import java.util.concurrent.TimeUnit

/**
 * Drives the Java DSL fixtures, so the shapes that must compile also demonstrably run.
 * <p>
 * The compile-time half of this guardrail has already happened by the time a single feature method
 * executes: {@link JavaDslSurface} is Java, so javac has resolved every overload in it, and an
 * ambiguous or uninferable call shape fails {@code test-compile} rather than reaching here. What
 * this spec adds is proof that the shapes are not merely legal but wired - a call that compiles
 * against the wrong overload would build the wrong machine, and Groovy specs on their own could
 * never have caught the ambiguity that motivated the fixture.
 */
class JavaDslSurfaceSpec extends Specification {

    def 'every mapper-bearing call shape builds and runs, synchronous and forked'() {
        given:
        def sm = JavaDslSurface.mapperCallSites()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the three synchronous members ran, mapped ones against the mapped context'
        result.success
        order.state == 's2'

        and: 'at least the synchronous ones - the forked three add to this as they land'
        order.trail.count { it == 'recording' } >= 1
        order.trail.count { it == 'notify:o-1' } >= 2

        cleanup:
        sm.close()
    }

    def 'every shape for declaring a context at the declaration site builds and runs'() {
        given:
        def sm = JavaDslSurface.declaredContextShapes()
        def order = new JavaDslSurface.Order()
        def ctx = new JavaDslSurface.OrderCtx()

        when:
        def result = sm.entity(order).transitionTo('s2', ctx)

        then:
        result.success

        and: 'a pass-through declaration widens, so the member receives the enclosing context itself'
        order.trail.count { it == 'pt:o-1' } == 1
        order.trail.count { it == 'widened:o-1' } == 1
        order.trail.contains('pt-op:o-1')
        order.trail.contains('pt-choice:o-1')

        and: 'a mapped declaration runs against the context its own mapper produced'
        order.trail.count { it == 'notify:o-1' } == 6
        order.trail.contains('mapped-op:o-1')
        order.trail.contains('mapped-choice:o-1')

        and: 'an implicitly-typed lambda in the mapper slot resolves to the same overloads'
        order.trail.contains('lambda-op:o-1')
        order.trail.contains('lambda-choice:o-1')

        and: 'a registered mapper named by id maps each of the four the same way'
        order.trail.contains('registered-op:o-1')
        order.trail.contains('registered-choice:o-1')

        and: "mapFrom writes back once the member completes, as at a mapped by-id call site"
        order.trail.contains('receipt:r-1')
        ctx.receipt == 'r-2'

        and: 'a declared context changes nothing about the reported path'
        result.executedPath*.toString().contains('op/mapped-op/mapped-op-step')
        result.executedPath*.toString().contains('op/mapped-choice/mapped-choice-step')

        cleanup:
        sm.close()
    }

    def 'every condition registration form resolves under the one condition name'() {
        given:
        def sm = JavaDslSurface.typedConditions()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'all eight pre-conditions held, so the step ran'
        result.success
        order.trail == ['recording']
    }

    def 'a choice registered at SM level builds and runs, in both registration forms'() {
        given:
        def sm = JavaDslSurface.registeredChoice()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail == ['recording', 'fallback']
        result.executedPath*.toString() == ['op', 'op/flat', 'op/flat/record',
                                            'op/scoped', 'op/scoped/fallback']

        cleanup:
        sm.close()
    }

    def 'a choice attached to a transition builds and runs'() {
        given:
        def sm = JavaDslSurface.transitionChoice()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the choice is the transition root, so no wrapper appears on the path'
        result.success
        order.trail == ['shared', 'recording']
        result.executedPath*.toString() == ['route', 'route/shared', 'route/record']

        cleanup:
        sm.close()
    }

    def 'a sequence declared in place builds and runs at every position that holds one'() {
        given:
        def sm = JavaDslSurface.inlineSequenceShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'each nested container ran, including the one two levels down'
        result.success
        order.trail.contains('in-container')
        order.trail.contains('nested')
        order.trail.contains('two-deep')
        order.trail.contains('in-branch')

        and: 'the default branch did not run, since the first branch matched'
        !order.trail.contains('in-default')

        and: 'nesting shows in the reported path'
        result.executedPath*.toString().contains('op/in-container/in-container-step')
        result.executedPath*.toString().contains('op/nested/two-deep/two-deep-step')

        cleanup:
        sm.close()
    }

    def 'the same grammar dispatched from inside an action body builds and runs'() {
        given:
        def sm = JavaDslSurface.dispatchFromActionBody()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail.contains('recording')
        order.trail.contains('notify:o-1')

        cleanup:
        sm.close()
    }

    def 'the execution logging shapes build and run'() {
        given:
        def sm = JavaDslSurface.executionLoggingShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail.contains('recording')

        cleanup:
        sm.close()
    }

    def 'the global listener disable shapes build and suppress only the globals'() {
        given:
        def sm = JavaDslSurface.globalListenerDisableShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail.contains('recording')
        !order.trail.any { it.startsWith('any-') }

        cleanup:
        sm.close()
    }

    def 'the async listener shapes build and run'() {
        given:
        def sm = JavaDslSurface.asyncListenerShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        waitFor { order.trail.containsAll(['state-async', 'transition-async', 'action-async']) }

        cleanup:
        sm.close()
    }

    def 'the fork shapes dispatched from inside an action body build and run'() {
        given:
        def sm = JavaDslSurface.forkFromActionBody()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the transition does not wait for any of them, and every branch still runs'
        result.success
        waitFor { order.trail.count { it == 'recording' } == 2 }
        waitFor { order.trail.count { it == 'notify:o-1' } == 4 }

        and: 'a branch reaches neither reported path'
        result.executedPath*.toString() == ['dispatch']

        cleanup:
        sm.close()
    }

    def 'the declaration shapes build and run'() {
        given:
        def sm = JavaDslSurface.declarationShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail.contains('inline')

        cleanup:
        sm.close()
    }

    def 'every branch member shape builds and runs, on a branch and on the default branch'() {
        given:
        def sm = JavaDslSurface.branchMemberShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the taken branch ran its members; the default branch was not reached'
        result.success
        order.trail.contains('branch-inline')
        !order.trail.contains('default-inline')

        cleanup:
        sm.close()
    }

    def 'the compensation shapes build, and the routed rollback runs'() {
        given:
        def sm = JavaDslSurface.compensationShapes()
        def order = new JavaDslSurface.Order()

        expect: 'nothing fails here; the chain compiling is the point, and it still executes'
        sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx()).success

        cleanup:
        sm.close()
    }

    def 'the executor configuration builds, forks, and closes'() {
        given:
        def sm = JavaDslSurface.executorConfiguration()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success

        and: 'the branch lands on a thread the host named'
        waitFor { order.trail.contains('recording') }

        cleanup:
        sm.close()
    }

    def "every member form resolves and runs at a transition position"() {
        given:
        def sm = JavaDslSurface.transitionSequenceShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the synchronous members ran in the order written'
        result.success
        order.state == 's2'
        order.trail.indexOf('inline:o-1') < order.trail.indexOf('widened:o-1')
        order.trail.contains('mapped:o-1')

        and: 'each member is a root of the executed path; only a container adds a level'
        def paths = result.executedPath*.toString()
        paths.first() == 'record'
        paths.contains('group')
        paths.contains('group/grouped')
        paths.contains('route/branch-member')

        and: 'the two synchronous notify references are on the path; the forked ones never are'
        paths.count { it == 'notify' } == 2
        paths.count { it == 'record' } == 1

        and: 'the forked members still run, landing after the transition returned'
        waitFor { order.trail.count { it == 'notify:o-1' } == 4 }
        waitFor { order.trail.count { it == 'recording' } == 7 }
        waitFor { order.trail.contains('t-forked') }

        cleanup:
        sm.close()
    }

    def 'every inline forked declaration builds, and each one leaves the transition'() {
        given:
        def sm = JavaDslSurface.forkedDeclarationShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'only the container that holds them is on the path; sixteen members are not'
        result.success
        result.executedPath*.toString() == ['op']

        and: 'one of each context shape lands on a branch'
        waitFor { order.trail.contains('f-instance') }
        waitFor { order.trail.contains('f-pt:o-1') }
        waitFor { order.trail.contains('f-mapped:o-1') }
        waitFor { order.trail.contains('f-registered:o-1') }

        cleanup:
        sm.close()
    }

    def 'every host entry point runs from Java, and its result reads back'() {
        when:
        def outcomes = JavaDslSurface.hostEntryPoints()

        then: 'every condition form passed, so every targeted call succeeded against the same transition'
        def succeeded = 'true:s1>s2:t:1:0:true:true:true'
        outcomes.findAll { it.endsWith(succeeded) }*.takeBefore(':') ==
            ['transitionTo', 'transitionTo-id', 'transitionTo-ctx', 'transitionTo-id-ctx',
             'executeTransition', 'executeTransition-id', 'fire', 'fire-ctx',
             'processEvent', 'processDataChange']

        and: 'the scanning entry points name the trigger that fired, or report that none did'
        outcomes.contains("processEvent:true:on-paid:$succeeded".toString())
        outcomes.contains('processEvent-ctx:false:null:false')
        outcomes.contains("processDataChange:when-registered:$succeeded".toString())
        outcomes.contains('processDataChange-ctx:true')
    }

    def 'every listener hook fires in both forms, and the transition may opt out of the globals'() {
        given:
        def sm = JavaDslSurface.listenerHookShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then:
        result.success
        order.trail.containsAll(['s-exit', 't-complete', 'START:t:direct:-', 'COMPLETE:t:direct:true',
                                 'g-action-start', 'g-action-complete', 'g-action-complete-cfg',
                                 'START:record:STEP:record:t:true:true',
                                 'COMPLETE:record:STEP:record:t:true:false',
                                 'EXIT:s1:t', 'ENTRY:s2:t'])

        and: 'the transition disabled its own category of globals, and nothing failed'
        !order.trail.any { it.startsWith('any:') }
        !order.trail.any { it.contains('error') }

        cleanup:
        sm.close()
    }

    def 'the registration shapes build and run'() {
        given:
        def sm = JavaDslSurface.registrationShapes()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'three mapper registrations carried three members across, and the first branch won'
        result.success
        order.trail == ['self-compensating', 'notify:o-1', 'notify:o-1', 'notify:o-1', 'labelled']

        cleanup:
        sm.close()
    }

    def 'a definition is replaced in place, and the next transition runs the new one'() {
        expect: 'generation 1 before the swap, 2 after, and one line from each version'
        JavaDslSurface.definitionReplacement() == '1:2:first,second'
    }

    def 'a refusal carries the ids a host would otherwise parse out of a message'() {
        expect:
        JavaDslSurface.refusalIds() == 'never:PRE_CONDITION:t'
    }

    def 'one set of components, written against a shared trait, drives a machine per entity type'() {
        given: 'two machines built from the same component instances'
        def (orders, shipments) = JavaDslSurface.sharedAcrossEntityTypes()
        def order = new JavaDslSurface.Order()
        def shipment = new JavaDslSurface.Shipment()

        when:
        def orderResult = orders.entity(order).transitionTo('s2')
        def shipmentResult = shipments.entity(shipment).transitionTo('s2')

        then: 'the shared resolver and applier drove both'
        orderResult.success
        shipmentResult.success
        order.currentState() == 's2'
        shipment.currentState() == 's2'

        and: 'the shared step ran at each of its three positions, on either entity'
        order.trail().count { it == 'tracked' } == 3
        shipment.trail().count { it == 'tracked' } == 3

        and: 'and so did each listener category'
        order.trail().any { it.startsWith('state:') }
        order.trail().any { it.startsWith('transition:') }
        order.trail().any { it.startsWith('action:') }
        shipment.trail().any { it.startsWith('state:') }
        shipment.trail().any { it.startsWith('transition:') }
        shipment.trail().any { it.startsWith('action:') }

        cleanup:
        orders.close()
        shipments.close()
    }

    def 'the state machine reports its own metadata, and an expression can pass the entity whole'() {
        expect:
        JavaDslSurface.stateMachineMetadata() == 'orders:Orders:What an order does:3:true'
    }

    def 'one registered trigger sits on two transitions and fires from either state'() {
        expect:
        JavaDslSurface.sharedTriggerShapes() == '[from-s1, from-s2]:from-s1:from-s2:[from-s1]:[from-s1]'
    }

    def 'every listener registration form attaches by id, across categories and scopes'() {
        expect: 'each attachment that fires delivered - a dropped one changes the sequence, not just its length'
        // The error hooks never fire on this success path; the build resolving their by-id references is their proof.
        JavaDslSurface.listenerRegistrationShapes() ==
            ('START,typed-transition-cfg,any,EXIT,exit-audit,START,untyped-action,recording,COMPLETE,scoped-action-instance,'
                + 'typed-action-cfg,untyped-action,untyped-action,recording,typed-action-cfg,'
                + 'typed-action-cfg,untyped-action,untyped-action,recording,typed-action-cfg,'
                + 'COMPLETE,scoped-transition-instance,any,ENTRY')
    }

    def 'a component factory is a lambda, and may delegate to the reflective default'() {
        expect:
        JavaDslSurface.componentFactoryShapes() == 'TrackStep:true:OrderIsOpen'
    }

    private static boolean waitFor(Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }
}

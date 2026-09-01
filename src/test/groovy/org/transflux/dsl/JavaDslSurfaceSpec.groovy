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
        order.trail.count { it == 'widened:o-1' } == 2
        order.trail.contains('pt-op:o-1')
        order.trail.contains('pt-cond:o-1')

        and: 'a mapped declaration runs against the context its own mapper produced'
        order.trail.count { it == 'notify:o-1' } == 3
        order.trail.contains('mapped-op:o-1')
        order.trail.contains('mapped-cond:o-1')

        and: "mapFrom writes back once the member completes, as at a mapped by-id call site"
        ctx.receipt == 'r-1'

        and: 'a declared context changes nothing about the reported path'
        result.executedPath*.toString().contains('op/mapped-op/mapped-op-step')
        result.executedPath*.toString().contains('op/mapped-cond/mapped-cond-step')

        cleanup:
        sm.close()
    }

    def 'a conditional registered at SM level builds and runs, in both registration forms'() {
        given:
        def sm = JavaDslSurface.registeredConditional()
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

    def 'a conditional attached to a transition builds and runs'() {
        given:
        def sm = JavaDslSurface.transitionConditional()
        def order = new JavaDslSurface.Order()

        when:
        def result = sm.entity(order).transitionTo('s2', new JavaDslSurface.OrderCtx())

        then: 'the conditional is the transition root, so no wrapper appears on the path'
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

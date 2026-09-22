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
import org.transflux.core.action.Compensation
import org.transflux.core.action.StepDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * A {@code java.lang.Error} is a failure like any other - rolled back, reported to listeners, then
 * rethrown - unless it says the JVM itself can no longer be trusted.
 */
class StateMachineImplErrorRollbackSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    def "an Error rolls the transition back, closes every start notification, and is rethrown"() {
        given:
        def log = []
        def results = []
        def sm = build({ d -> d
            .onAnyTransitionStart("g-start", { e, c, x -> log << "start" } as TransitionListener)
            .onAnyTransitionError("g-error", { e, c, x -> log << "error"; results << x.result() } as TransitionListener)
            .onAnyActionError("g-action-error", { e, c, x -> log << ("action-error:" + x.actionId()) } as ActionListener)
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "release" } as Compensation) } as Consumer)
            .step("assert", failingWith(new AssertionError("boom")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("reserve").run("assert") })
            .state("s2") })
        def entity = new Entity("s1")

        when:
        sm.entity(entity).transitionTo("s2")

        then: "the caller gets the Error itself, never a result"
        thrown(AssertionError)

        and: "but everything a failure owes was done first"
        log == ["start", "action-error:assert", "release", "error"]
        results.size() == 1
        !results[0].success
        results[0].error instanceof AssertionError
        results[0].compensatedPath*.toString() == ["reserve"]
        entity.state == "s1"

        when: "the reentrancy guard was released on the way out, so the entity can be driven again"
        sm.entity(entity).transitionTo("s2")

        then: "it fails the same way, rather than as a reentrant call"
        thrown(AssertionError)
    }

    def "a VirtualMachineError abandons the rollback and notifies nobody"() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyTransitionError("g-error", { e, c, x -> log << "error" } as TransitionListener)
            .onAnyActionError("g-action-error", { e, c, x -> log << "action-error" } as ActionListener)
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "release" } as Compensation) } as Consumer)
            .step("die", failingWith(new InternalError("synthetic")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("reserve").run("die") })
            .state("s2") })

        when:
        sm.entity(new Entity("s1")).transitionTo("s2")

        then:
        thrown(InternalError)
        log.isEmpty()
    }

    def "a route matches an Error like any other failure"() {
        given:
        def log = []
        def sm = build({ d -> d
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "fallback" } as Compensation)
                .forException(AssertionError)
                    .withCompensation({ e, c -> log << "routed" } as Compensation) } as Consumer)
            .step("assert", failingWith(new AssertionError("boom")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("reserve").run("assert") })
            .state("s2") })

        when:
        sm.entity(new Entity("s1")).transitionTo("s2")

        then:
        thrown(AssertionError)
        log == ["routed"]
    }

    def "a route for a VirtualMachineError is refused, since it could never match"() {
        when:
        build({ d -> d
            .step("reserve", { StepDef s -> s
                .using(noop())
                .forException(OutOfMemoryError)
                    .withCompensation({ e, c -> } as Compensation) } as Consumer) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("OutOfMemoryError")
        e.message.contains("never match")
    }

    def "an Error from an observer is contained like an exception, and fails nothing"() {
        given:
        def log = []
        def sm = build({ d -> d
            .onAnyTransitionComplete("g-complete", { e, c, x -> throw new AssertionError("observer") } as TransitionListener)
            .onAnyTransitionError("g-error", { e, c, x -> log << "error" } as TransitionListener)
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "release" } as Compensation) } as Consumer)
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("reserve") })
            .state("s2") })
        def entity = new Entity("s1")

        when:
        def result = sm.entity(entity).transitionTo("s2")

        then:
        result.success
        log.isEmpty()
        entity.state == "s2"
    }

    def "an Error from a compensation does not cost the entries beneath it their rollback"() {
        given:
        def log = []
        def sm = build({ d -> d
            .step("first", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "undo-first" } as Compensation) } as Consumer)
            .step("second", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> throw new AssertionError("undo failed") } as Compensation) } as Consumer)
            .step("fail", failingWith(new IllegalStateException("boom")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("first").run("second").run("fail") })
            .state("s2") })

        when:
        def result = sm.entity(new Entity("s1")).transitionTo("s2")

        then: "the transition still reports what actually failed"
        result.error instanceof IllegalStateException
        result.compensatedPath*.toString() == ["second", "first"]
        log == ["undo-first"]
    }

    def "an Error from a route guard is a non-match, like an exception"() {
        given:
        def log = []
        def sm = build({ d -> d
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> log << "fallback" } as Compensation)
                .forException(IllegalStateException)
                    .matching({ throw new AssertionError("guard") })
                    .withCompensation({ e, c -> log << "routed" } as Compensation) } as Consumer)
            .step("fail", failingWith(new IllegalStateException("boom")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t.run("reserve").run("fail") })
            .state("s2") })

        when:
        def result = sm.entity(new Entity("s1")).transitionTo("s2")

        then:
        result.error instanceof IllegalStateException
        log == ["fallback"]
    }

    def "an Error on a forked branch rolls that branch back"() {
        given:
        def released = new CountDownLatch(1)
        // The rethrown Error ends the worker; the handler keeps its stack trace out of the build log.
        ThreadFactory quiet = { Runnable r ->
            def thread = new Thread(r)
            thread.daemon = true
            thread.uncaughtExceptionHandler = { th, ex -> } as Thread.UncaughtExceptionHandler
            return thread
        }
        StateMachine<Entity> sm = build({ d -> d
            .withAsyncPool(1, 4, quiet)
            .step("reserve", { StepDef s -> s
                .using(noop())
                .withCompensation({ e, c -> released.countDown() } as Compensation) } as Consumer)
            .step("assert", failingWith(new AssertionError("boom")))
            .state("s1")
            .transition("t", "s1", "s2", { t -> t
                .forkOperation("branch", { op -> op.run("reserve").run("assert") }) })
            .state("s2") })

        when:
        def result = sm.entity(new Entity("s1")).transitionTo("s2")

        then:
        result.success
        released.await(5, TimeUnit.SECONDS)

        cleanup:
        sm?.close()
    }

    private static Action<Entity, Object> noop() {
        return { e, ctx, tr -> } as Action
    }

    private static Action<Entity, Object> failingWith(Throwable failure) {
        return { e, ctx, tr -> throw failure } as Action
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

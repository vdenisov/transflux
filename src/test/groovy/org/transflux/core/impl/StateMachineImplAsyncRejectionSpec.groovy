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

import ch.qos.logback.classic.Level
import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.exception.TransfluxReentrancyException
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * What each rejection policy does to work the executor cannot take, and whose declaration decides
 * it - the action's own, or the state machine's default.
 */
class StateMachineImplAsyncRejectionSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    StateMachine<Entity> sm
    LogCapture capture

    def cleanup() {
        capture?.stop()
        sm?.close()
    }

    // ----- BLOCK -----

    def 'BLOCK waits for a slot rather than losing the work'() {
        given: 'a pool of one thread and one queue slot, with its worker held'
        def release = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        def allDone = new CountDownLatch(3)
        sm = build({ smd ->
            smd.withAsyncPool(1, 1).withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               .step('hold', { e, c, t ->
                   release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                   ran.add('hold')
                   allDone.countDown()
               } as Action)
               .step('queued', { e, c, t -> ran.add('queued'); allDone.countDown() } as Action)
               .step('blocked', { e, c, t -> ran.add('blocked'); allDone.countDown() } as Action)
        }, { op -> op.fork('hold').fork('queued').fork('blocked') })
        capture = LogCapture.start('org.transflux.execution.async')

        when: 'the transition runs on a thread of its own, so the third fork can park'
        def result = new ConcurrentLinkedQueue()
        def caller = new Thread({ result.add(sm.executeTransition(new Entity('s1'), 's2')) })
        caller.start()

        then: 'it is still parked on the third submission while the worker is held'
        caller.join(300)
        caller.isAlive()

        when: 'the worker is let go'
        release.countDown()
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        then: 'every branch ran, and entering the waiting state was reported once'
        allDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
        ran.size() == 3
        result.peek().success
        capture.messagesAtOrAbove(Level.WARN)
              .any { it.startsWith('Async pool saturated') && it.contains('op/blocked') }
    }

    def 'BLOCK fails the transition once the pool is closed, since no wait can help'() {
        given:
        sm = build({ smd ->
            smd.withAsyncPool(1, 1).withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               .step('notify', { e, c, t -> } as Action)
        }, { op -> op.fork('notify') })
        sm.close()

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'a queue nothing will drain is a refusal, not something to wait on'
        !result.success
        result.error instanceof RejectedExecutionException
    }

    def 'BLOCK fails the transition when the waiting thread is interrupted'() {
        given: 'one slot, held by a branch that never finishes on its own'
        def release = new CountDownLatch(1)
        sm = build({ smd ->
            smd.withAsyncPool(1, 1).withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               .step('hold', { e, c, t -> release.await(WAIT_SECONDS, TimeUnit.SECONDS) } as Action)
               .step('queued', { e, c, t -> } as Action)
               .step('blocked', { e, c, t -> } as Action)
        }, { op -> op.fork('hold').fork('queued').fork('blocked') })

        when:
        def outcome = new ConcurrentLinkedQueue()
        def caller = new Thread({
            def r = sm.executeTransition(new Entity('s1'), 's2')
            outcome.add([r, Thread.currentThread().isInterrupted()])
        })
        caller.start()
        caller.join(300)
        caller.interrupt()
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        then: 'the transition fails, and the interrupt is left standing for the host'
        def (result, interrupted) = outcome.peek()
        !result.success
        result.error instanceof RejectedExecutionException
        interrupted

        cleanup:
        release.countDown()
    }

    def 'BLOCK from inside a branch runs inline rather than deadlocking the pool'() {
        given: 'a forked container that forks twice, on the one thread and one slot it runs on'
        def ran = new ConcurrentLinkedQueue<String>()
        def done = new CountDownLatch(3)
        sm = build({ smd ->
            smd.withAsyncPool(1, 1).withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               .step('first', { e, c, t -> ran.add('first'); done.countDown() } as Action)
               .step('second', { e, c, t -> ran.add('second'); done.countDown() } as Action)
               .operation('outer', Object, { OperationDef<Entity, Object> op ->
                   // The first fork fills the only queue slot, and it cannot start until this
                   // container returns the one worker. Waiting for the second slot here would be
                   // waiting for work that only this thread could run.
                   op.fork('first')
                     .fork('second')
                     .step('outer-body', { e, c, t -> ran.add('outer'); done.countDown() } as Action)
               } as Consumer)
        }, { op -> op.fork('outer') })
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the second nested fork completes instead of parking a worker on its own pool'
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        ran.containsAll(['outer', 'first', 'second'])
        capture.messages().any {
            it.startsWith('Async work running inline') && it.contains('deadlock')
        }
    }

    def 'BLOCK against a host-supplied executor fails the build'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()

        when:
        build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               .step('notify', { entity, ctx, tr -> } as Action)
        }, { op -> op.fork('notify') })

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains('BLOCK')
        ex.message.contains('withAsyncPool')

        cleanup:
        hostPool.shutdownNow()
    }

    def 'BLOCK declared on an action fails the build against a host-supplied executor'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()

        when:
        build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .step('notify', { StepDef<Entity, Object> step ->
                   step.using({ entity, ctx, tr -> } as Action)
                       .withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
               } as Consumer)
        }, { op -> op.fork('notify') })

        then: 'the message names the offending def rather than the machine'
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("step 'notify'")

        cleanup:
        hostPool.shutdownNow()
    }

    def 'the pool admits everything it has room for, running plus queued'() {
        given: 'one worker and one queue slot, so two submissions fit with none waiting'
        def release = new CountDownLatch(1)
        def done = new CountDownLatch(2)
        sm = build({ smd ->
            smd.withAsyncPool(1, 1)
               .step('hold', { e, c, t ->
                   release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                   done.countDown()
               } as Action)
               .step('queued', { e, c, t -> done.countDown() } as Action)
        }, { op -> op.fork('hold').fork('queued') })
        capture = LogCapture.start('org.transflux.execution.async')

        when: 'the first occupies the worker and the second takes the empty queue slot'
        def result = sm.executeTransition(new Entity('s1'), 's2')
        release.countDown()

        then: 'neither is refused - the executor decides admission, and nothing shadows it'
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        capture.messages().every { !it.startsWith('Async branch not started') }
    }

    // ----- CALLER_RUNS -----

    def 'CALLER_RUNS runs the refused branch on the submitting thread'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def ranOn = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.CALLER_RUNS)
               .step('notify', { e, c, t -> ranOn.add(Thread.currentThread().name) } as Action)
        }, { op -> op.fork('notify') })
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        ranOn.peek() == Thread.currentThread().name
        capture.messages().any { it.startsWith('Async work running inline') }
    }

    def 'a branch run inline still keeps its failure to itself'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.CALLER_RUNS)
               .step('notify', { e, c, t -> throw new IllegalStateException('boom') } as Action)
        }, { op -> op.fork('notify') })
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'fire-and-forget holds even when "forget" happened on the caller thread'
        result.success
        result.error == null
        !result.executedPath.any { it.toString().contains('notify') }
        capture.messages().any { it.startsWith('Async branch failed') }
    }

    def 'a branch run inline may not drive the state machine that forked it'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def caught = new ConcurrentLinkedQueue<Throwable>()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.CALLER_RUNS)
               .step('notify', { e, c, t ->
                   try {
                       sm.entity(new Entity('s1')).transitionTo('s2')
                   } catch (Throwable ex) {
                       caught.add(ex)
                   }
               } as Action)
        }, { op -> op.fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the ban travels with the task, not with the thread it was expected to run on'
        result.success
        caught.peek() instanceof TransfluxReentrancyException
    }

    // ----- per-def override -----

    def 'an action declaring a policy overrides the state machine default'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(machineDefault)
               .step('notify', { StepDef<Entity, Object> step ->
                   step.using({ e, c, t -> } as Action)
                   if (declared != null) {
                       step.withAsyncRejectionPolicy(declared)
                   }
               } as Consumer)
        }, { op -> op.fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success == succeeds

        cleanup:
        hostPool.shutdownNow()

        where:
        machineDefault                  | declared                        || succeeds
        AsyncRejectionPolicy.DROP       | AsyncRejectionPolicy.FAIL       || false
        AsyncRejectionPolicy.FAIL       | AsyncRejectionPolicy.DROP       || true
        AsyncRejectionPolicy.FAIL       | AsyncRejectionPolicy.CALLER_RUNS || true
        AsyncRejectionPolicy.DROP       | null                            || true
        AsyncRejectionPolicy.FAIL       | null                            || false
    }

    def 'a by-id fork declaring a policy at its position overrides both the def and the machine'() {
        given: 'a def that says FAIL, forked from a position that says otherwise'
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def ranOn = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(machineDefault)
               .step('notify', { StepDef<Entity, Object> step ->
                   step.using({ e, c, t -> ranOn.add(Thread.currentThread().name) } as Action)
                       .withAsyncRejectionPolicy(AsyncRejectionPolicy.FAIL)
               } as Consumer)
        }, { op -> op.fork('notify', atPosition) })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the position is the most specific statement, and it wins'
        result.success == succeeds
        (ranOn.peek() == Thread.currentThread().name) == inline

        cleanup:
        hostPool.shutdownNow()

        where:
        machineDefault             | atPosition                       || succeeds | inline
        AsyncRejectionPolicy.DROP  | AsyncRejectionPolicy.DROP        || true     | false
        AsyncRejectionPolicy.FAIL  | AsyncRejectionPolicy.DROP        || true     | false
        AsyncRejectionPolicy.DROP  | AsyncRejectionPolicy.CALLER_RUNS || true     | true
    }

    def 'the mapped by-id shapes carry the position policy too'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.DROP)
               .mapper('same', Object, Object, { parent -> parent } as ContextMapper)
               .step('notify', { e, c, t -> } as Action)
        }, members)

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        !result.success
        result.error instanceof RejectedExecutionException

        cleanup:
        hostPool.shutdownNow()

        where:
        shape          | members
        'mapper by id' | { op -> op.fork('notify', 'same', AsyncRejectionPolicy.FAIL) }
        'inline mapper'| { op -> op.fork('notify', { parent -> parent } as ContextMapper, AsyncRejectionPolicy.FAIL) }
    }

    def 'BLOCK declared at a by-id fork fails the build against a host-supplied executor'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()

        when:
        build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .step('notify', { entity, ctx, tr -> } as Action)
        }, { op -> op.fork('notify', AsyncRejectionPolicy.BLOCK) })

        then: 'the message names the fork rather than the machine'
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("a fork of action 'notify'")

        cleanup:
        hostPool.shutdownNow()
    }

    def 'a bare instance member has no def and takes the machine default'() {
        given: 'the same action, registered as an instance rather than through a configurer'
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .withAsyncRejectionPolicy(AsyncRejectionPolicy.FAIL)
               .step('notify', { e, c, t -> } as Action)
        }, { op -> op.fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        !result.success
        result.error instanceof RejectedExecutionException

        cleanup:
        hostPool.shutdownNow()
    }

    // ----- the queue bound -----

    def 'an executor throwing something other than a rejection is broken host code, and fails'() {
        given: 'a host executor whose first submission blows up in a way no policy covers'
        def attempts = new ConcurrentLinkedQueue<String>()
        ExecutorService hostPool = new ThreadPoolExecutor(
            1, 1, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4)) {
            @Override
            void execute(Runnable command) {
                attempts.add('x')
                if (attempts.size() == 1) {
                    throw new IllegalStateException('cannot start a thread')
                }
                super.execute(command)
            }
        }
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .step('notify', { e, c, t -> done.countDown() } as Action)
        }, { op -> op.fork('notify') })
        def entity = new Entity('s1')

        when: 'the first transition fails on the way out'
        def failed = sm.executeTransition(entity, 's2')

        then: 'a refusal is a policy question; anything else is a broken executor, and propagates'
        !failed.success
        failed.error instanceof IllegalStateException

        when: 'a second transition submits to the same executor'
        entity.state = 's1'
        def result = sm.executeTransition(entity, 's2')

        then: 'nothing was left behind by the failed submission'
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        cleanup:
        hostPool.shutdownNow()
    }

    private StateMachine<Entity> build(Closure smConfig, Closure members) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        smConfig.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.operation('op', { OperationDef<Entity, Object> op -> members.call(op) } as Consumer)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }
}

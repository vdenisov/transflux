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
import org.transflux.core.action.ForkRejectionPolicy
import org.transflux.core.action.OperationDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Who owns the executor, and what closing the state machine does to it.
 */
class StateMachineImplAsyncExecutorSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'a definition that never forks builds no pool, and close does nothing'() {
        given:
        capture = LogCapture.start('org.transflux.execution.async')
        def sm = build({ smd -> smd.step('plain', { e, c, t -> } as Action) },
                       { op -> op.run('plain') }, { smd -> })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        sm.close()
        sm.close()

        then:
        capture.messages().isEmpty()
    }

    def 'a framework pool is announced once at build, and a forking transition announces nothing'() {
        given:
        def done = new CountDownLatch(1)
        capture = LogCapture.start('org.transflux.execution.async')
        def sm = buildForking(done, { smd -> smd.withAsyncPool(2, 8) })

        when: 'the build line is the only INFO the async leaf ever emits'
        def atBuild = capture.messagesAtOrAbove(Level.INFO)
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        atBuild.size() == 1
        atBuild.first().contains('Async pool created')
        atBuild.first().contains('threads=2')
        atBuild.first().contains('queueCapacity=8')
        capture.messagesAtOrAbove(Level.INFO).size() == 1

        cleanup:
        sm.close()
    }

    def 'a framework pool uses daemon threads, named for the framework'() {
        given:
        def done = new CountDownLatch(1)
        def threads = new ConcurrentLinkedQueue<String>()
        def daemon = new ConcurrentLinkedQueue<Boolean>()
        def sm = build({ smd ->
            smd.step('notify', { e, c, t ->
                threads.add(Thread.currentThread().name)
                daemon.add(Thread.currentThread().daemon)
                done.countDown()
            } as Action)
        }, { op -> op.fork('notify') }, { smd -> })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        threads.peek().startsWith('transflux-async-')
        daemon.peek()

        cleanup:
        sm.close()
    }

    def 'a host-supplied executor is left running by close'() {
        given:
        def done = new CountDownLatch(1)
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        def sm = buildForking(done, { smd -> smd.withAsyncExecutor(hostPool) })

        when:
        sm.close()

        then: 'a pool shared with the rest of an application is not ours to shut down'
        !hostPool.isShutdown()

        cleanup:
        hostPool.shutdownNow()
    }

    def 'a rejected submission under DROP leaves the transition successful'() {
        given: 'a closed executor refuses everything'
        def done = new CountDownLatch(1)
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def sm = buildForking(done, { smd -> smd.withAsyncExecutor(hostPool) })
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        capture.messages().any { it.contains('Async branch not started') && it.contains('op/notify') }

        cleanup:
        sm.close()
    }

    def 'a rejected submission under FAIL fails the transition'() {
        given:
        def done = new CountDownLatch(1)
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def sm = buildForking(done, { smd ->
            smd.withAsyncExecutor(hostPool).withForkRejectionPolicy(ForkRejectionPolicy.FAIL)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        !result.success
        result.error instanceof RejectedExecutionException

        cleanup:
        sm.close()
    }

    def 'declaring both an executor and a pool warns and takes the later one'() {
        given:
        capture = LogCapture.start('org.transflux.build.validation')
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        def done = new CountDownLatch(1)

        when:
        def sm = buildForking(done, { smd -> smd.withAsyncExecutor(hostPool).withAsyncPool(1, 1) })

        then:
        capture.messages().any {
            it.contains('Definition value overwritten') && it.contains('field=Async executor')
        }

        and: 'the pool won, so the host executor was never used'
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        !hostPool.isShutdown()

        cleanup:
        sm.close()
        hostPool.shutdownNow()
    }

    def 'an unusable pool size is rejected at declaration'() {
        when:
        new StateMachineDefImpl<Entity>().withAsyncPool(0, 10)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('thread count must be positive')
    }

    def 'a definition that forks only from inside a branch still builds a pool'() {
        given: 'the fork walk has to descend into a conditional to see this one'
        def done = new CountDownLatch(1)
        capture = LogCapture.start('org.transflux.execution.async')
        def sm = build({ smd -> smd.step('notify', { e, c, t -> done.countDown() } as Action) },
                       { op ->
                           op.conditional('route', { cs ->
                               cs.branch('only', { b ->
                                   b.condition('always', { e -> true } as Predicate).fork('notify')
                               } as Consumer)
                           } as Consumer)
                       }, { smd -> })

        when: 'without the descent there is no executor, and the branch fails at submission'
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        done.count == 0
        capture.messages().any { it.contains('Async pool created') }

        cleanup:
        sm.close()
    }

    def 'a definition that forks only from inside an inline container still builds a pool'() {
        given: 'the fork walk has to descend into a container declared in place to see this one'
        def done = new CountDownLatch(1)
        capture = LogCapture.start('org.transflux.execution.async')
        def sm = build({ smd -> smd.step('notify', { e, c, t -> done.countDown() } as Action) },
                       { op ->
                           op.operation('nested', { nested -> nested.fork('notify') } as Consumer)
                       }, { smd -> })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        done.count == 0
        capture.messages().any { it.contains('Async pool created') }

        cleanup:
        sm.close()
    }

    def 'a definition that forks only from a transition-attached conditional still builds a pool'() {
        given: 'the fork walk reaches an attached conditional, which is not an operation'
        def done = new CountDownLatch(1)
        capture = LogCapture.start('org.transflux.execution.async')
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        builder.step('notify', { e, c, t -> done.countDown() } as Action)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.conditional('route', { cs ->
                    cs.branch('only', { b ->
                        b.condition('always', { e -> true } as Predicate).fork('notify')
                    } as Consumer)
                } as Consumer)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        def sm = smd.build()

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        done.count == 0
        capture.messages().any { it.contains('Async pool created') }

        cleanup:
        sm.close()
    }

    private StateMachine<Entity> buildForking(CountDownLatch done, Closure asyncConfig) {
        return build({ smd -> smd.step('notify', { e, c, t -> done.countDown() } as Action) },
                     { op -> op.fork('notify') }, asyncConfig)
    }

    private StateMachine<Entity> build(Closure registrations, Closure members, Closure asyncConfig) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        asyncConfig.call(builder)
        registrations.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.operation('op', { OperationDef<Entity, Object> op -> members.call(op) } as Consumer)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }
}

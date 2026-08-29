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
import org.transflux.core.action.Compensation
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.ForkableContext
import org.transflux.core.action.OperationDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Which context a branch runs against, and who produced it: a call-site mapper first, then the
 * context's own fork, then the enclosing reference.
 */
class StateMachineImplForkContextSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class ForkableCtx implements ForkableContext<ForkableCtx> {
        static final AtomicInteger FORKS = new AtomicInteger()
        static final ConcurrentLinkedQueue<String> FORK_THREADS = new ConcurrentLinkedQueue<>()

        String tag

        ForkableCtx(String tag) {
            this.tag = tag
        }

        @Override
        ForkableCtx fork() {
            FORKS.incrementAndGet()
            FORK_THREADS.add(Thread.currentThread().name)
            return new ForkableCtx(tag)
        }
    }

    /**
     * A real class rather than a coerced closure: Groovy's closure coercion implements every
     * method of the interface, so it would look to the build like a mapper that writes back.
     */
    static class ToChild implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return new ChildCtx('mapped') }
    }

    static class ThrowingMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { throw new IllegalStateException('cannot project') }
    }

    static class ChildCtx {
        String tag

        ChildCtx(String tag) {
            this.tag = tag
        }
    }

    static class PlainCtx {
        String tag

        PlainCtx(String tag) {
            this.tag = tag
        }
    }

    ExecutorService executor
    StateMachine<Entity> sm

    def setup() {
        executor = Executors.newSingleThreadExecutor()
        ForkableCtx.FORKS.set(0)
        ForkableCtx.FORK_THREADS.clear()
    }

    def cleanup() {
        sm?.close()
        executor.shutdownNow()
    }

    def 'fork is invoked once per forked member, on the submitting thread'() {
        given:
        def done = new CountDownLatch(2)
        def seen = new ConcurrentLinkedQueue<Object>()
        sm = build(ForkableCtx, { smd ->
            smd.step('one', { e, c, t -> seen.add(c); done.countDown() } as Action)
             .step('two', { e, c, t -> seen.add(c); done.countDown() } as Action)
        }, { op -> op.fork('one').fork('two') })
        def parent = new ForkableCtx('parent')

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', parent)
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'one fork per branch, not one per operation'
        ForkableCtx.FORKS.get() == 2

        and: 'produced before submission, so on the thread that was executing the transition'
        ForkableCtx.FORK_THREADS.every { it == Thread.currentThread().name }

        and: 'each branch got an independent copy'
        seen.size() == 2
        seen.every { !it.is(parent) }
        seen.toList()[0] != null && !seen.toList()[0].is(seen.toList()[1])
    }

    def 'a call-site mapper wins over a forkable context'() {
        given:
        def done = new CountDownLatch(1)
        def seen = new ConcurrentLinkedQueue<Object>()
        sm = build(ForkableCtx, { smd ->
            smd.step('one', { e, c, t -> seen.add(c); done.countDown() } as Action)
        }, { op -> op.fork('one', new ToChild()) })

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', new ForkableCtx('parent'))
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        seen.peek() instanceof ChildCtx
        ForkableCtx.FORKS.get() == 0
    }

    def 'without a mapper or a forkable context the branch shares the reference'() {
        given:
        def done = new CountDownLatch(1)
        def seen = new ConcurrentLinkedQueue<Object>()
        sm = build(PlainCtx, { smd ->
            smd.step('one', { e, c, t -> seen.add(c); done.countDown() } as Action)
        }, { op -> op.fork('one') })
        def parent = new PlainCtx('parent')

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', parent)
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        seen.peek().is(parent)
    }

    def 'a fork that throws fails the transition that was about to spawn the branch'() {
        given:
        sm = build(ThrowingCtx, { smd ->
            smd.step('one', { e, c, t -> } as Action)
        }, { op -> op.fork('one') })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ThrowingCtx())

        then: 'host code that cannot produce a context is a broken definition, not lost work'
        !result.success
        result.error instanceof IllegalStateException
    }

    def 'a mapTo that throws fails the transition'() {
        given:
        sm = build(PlainCtx, { smd ->
            smd.step('one', { e, c, t -> } as Action)
        }, { op -> op.fork('one', new ThrowingMapper()) })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new PlainCtx('parent'))

        then:
        !result.success
        result.error instanceof IllegalStateException
    }

    def 'a compensation inside a branch receives the branch context'() {
        given:
        def done = new CountDownLatch(1)
        def compensated = new ConcurrentLinkedQueue<Object>()
        sm = build(ForkableCtx, { smd ->
            smd.step('one', { e, c, t -> throw new IllegalStateException('branch failed') } as Action)
        }, { op ->
            op.fork('one')
        })
        def parent = new ForkableCtx('parent')

        when: 'the forked action declares its rollback dynamically'
        sm = buildWithCompensation(compensated, done)
        sm.entity(new Entity('s1')).transitionTo('s2', parent)
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        compensated.size() == 1
        !compensated.peek().is(parent)
    }

    static class ThrowingCtx implements ForkableContext<ThrowingCtx> {
        @Override
        ThrowingCtx fork() {
            throw new IllegalStateException('cannot fork')
        }
    }

    private StateMachine<Entity> buildWithCompensation(ConcurrentLinkedQueue<Object> compensated,
                                                       CountDownLatch done) {
        def action = new Action<Entity, Object>() {
            @Override
            void execute(Entity entity, Object context,
                         org.transflux.core.transition.ExecutingTransition<Entity, Object> transition) {
                throw new IllegalStateException('branch failed')
            }

            @Override
            Compensation<Entity, Object> getCompensation(Entity entity, Object context) {
                return { e, ctx ->
                    compensated.add(ctx)
                    done.countDown()
                } as Compensation
            }
        }
        return build(ForkableCtx, { smd -> smd.step('one', action) }, { op -> op.fork('one') })
    }

    private StateMachine<Entity> build(Class ctxType, Closure registrations, Closure members) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .withAsyncExecutor(executor)
        registrations.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', ctxType, { t ->
                t.operation('op', { OperationDef<Entity, Object> op -> members.call(op) } as Consumer)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }
}

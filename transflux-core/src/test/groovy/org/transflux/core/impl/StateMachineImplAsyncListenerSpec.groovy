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
import org.transflux.core.action.ActionListener
import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.action.ForkableContext
import org.transflux.core.exception.TransfluxReentrancyException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * What declaring a listener async changes: where and when it runs, which context it sees, and what
 * happens when the executor will not take it. What it must not change - a listener never gates the
 * transition - is asserted alongside each.
 */
class StateMachineImplAsyncListenerSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx implements ForkableContext<Ctx> {
        Ctx origin

        @Override
        Ctx fork() {
            return new Ctx(origin: this)
        }
    }

    static class UnforkableCtx implements ForkableContext<UnforkableCtx> {
        @Override
        UnforkableCtx fork() {
            throw new IllegalStateException('cannot copy')
        }
    }

    StateMachine<Entity> sm
    LogCapture capture

    def cleanup() {
        capture?.stop()
        sm?.close()
    }

    // ----- where and when it runs -----

    def 'an async state listener runs on another thread, and the transition does not wait for it'() {
        given:
        def release = new CountDownLatch(1)
        def done = new CountDownLatch(1)
        def ranOn = new ConcurrentLinkedQueue<String>()
        sm = build({ smd -> }, {}, { st ->
            st.onEntry('enter', { l ->
                l.using({ e, c, change ->
                    ranOn.add(Thread.currentThread().name)
                    release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    done.countDown()
                } as StateListener).withAsync()
            } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the transition has returned while the listener is still held'
        result.success
        done.count == 1

        when:
        release.countDown()

        then:
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        ranOn.peek() != Thread.currentThread().name
    }

    def 'an async transition listener runs on another thread'() {
        given:
        def done = new CountDownLatch(1)
        def ranOn = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.onAnyTransitionComplete('audit', { l ->
                l.using({ e, c, execution ->
                    ranOn.add(Thread.currentThread().name)
                    done.countDown()
                } as TransitionListener).withAsync()
            } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        ranOn.peek() != Thread.currentThread().name
    }

    def 'an async action listener runs on another thread'() {
        given:
        def done = new CountDownLatch(1)
        def paths = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('work', { e, c, t -> } as Action)
               .onAnyActionComplete('watch', { l ->
                   l.using({ e, c, execution ->
                       if (Thread.currentThread().name.startsWith('transflux-async')) {
                           paths.add(execution.path().toString())
                       }
                       done.countDown()
                   } as ActionListener).withAsync()
               } as Consumer)
        }, { t -> t.run('work') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        paths.toList() == ['work']
    }

    def 'async listeners are submitted in declaration order, and a sync one after them still runs in line'() {
        given: 'a pool of one, so submission order is the order they run in'
        def done = new CountDownLatch(2)
        def order = new ConcurrentLinkedQueue<String>()
        def syncThread = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncPool(1, 4)
               .onAnyTransitionComplete('first', { l ->
                   l.using({ e, c, x -> order.add('first'); done.countDown() } as TransitionListener)
                    .withAsync()
               } as Consumer)
               .onAnyTransitionComplete('second', { l ->
                   l.using({ e, c, x -> order.add('second'); done.countDown() } as TransitionListener)
                    .withAsync()
               } as Consumer)
               .onAnyTransitionComplete('in-line', { e, c, x ->
                   syncThread.add(Thread.currentThread().name)
               } as TransitionListener)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        syncThread.toList() == [Thread.currentThread().name]
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        order.toList() == ['first', 'second']
    }

    def 'a throwing async listener is logged and swallowed, and the next listener still runs'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd -> }, {}, { st ->
            st.onEntry('broken', { l ->
                l.using({ e, c, change -> throw new IllegalStateException('boom') } as StateListener)
                 .withAsync()
            } as Consumer)
              .onEntry('after', { e, c, change -> done.countDown() } as StateListener)
        })
        capture = LogCapture.start('org.transflux.execution.listener')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        waitFor { capture.messagesAtOrAbove(Level.WARN).any { it.startsWith('State listener threw') } }
    }

    def 'an async action listener fires for an action running on a forked branch'() {
        given:
        def done = new CountDownLatch(1)
        def paths = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> } as Action)
               .onAnyActionComplete('watch', { l ->
                   l.using({ e, c, execution ->
                       paths.add(execution.path().toString())
                       done.countDown()
                   } as ActionListener).withAsync(AsyncRejectionPolicy.BLOCK)
               } as Consumer)
        }, { t -> t.fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        paths.toList() == ['notify']
    }

    // ----- context -----

    def 'an async listener gets a fork of a forkable context, and a sync listener the context itself'() {
        given:
        def done = new CountDownLatch(1)
        def seenAsync = new ConcurrentLinkedQueue<Ctx>()
        def seenSync = new ConcurrentLinkedQueue<Ctx>()
        sm = build({ smd ->
            smd.onAnyTransitionComplete('async', { l ->
                l.using({ e, Ctx c, x -> seenAsync.add(c); done.countDown() } as TransitionListener)
                 .withAsync()
            } as Consumer)
               .onAnyTransitionComplete('sync', { e, Ctx c, x -> seenSync.add(c) } as TransitionListener)
        })
        def ctx = new Ctx()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', ctx)

        then:
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        seenSync.peek().is(ctx)
        !seenAsync.peek().is(ctx)
        seenAsync.peek().origin.is(ctx)
    }

    def 'an async listener shares a context that cannot fork'() {
        given:
        def done = new CountDownLatch(1)
        def seen = new ConcurrentLinkedQueue<Object>()
        sm = build({ smd ->
            smd.onAnyTransitionComplete('async', { l ->
                l.using({ e, c, x -> seen.add(c); done.countDown() } as TransitionListener).withAsync()
            } as Consumer)
        })
        def ctx = [plain: true]

        when:
        sm.entity(new Entity('s1')).transitionTo('s2', ctx)

        then:
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        seen.peek().is(ctx)
    }

    def 'a context that fails to fork loses that async notification only'() {
        given:
        def asyncRan = new ConcurrentLinkedQueue<String>()
        def syncRan = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.onAnyTransitionComplete('async', { l ->
                l.using({ e, c, x -> asyncRan.add('async') } as TransitionListener).withAsync()
            } as Consumer)
               .onAnyTransitionComplete('sync', { e, c, x -> syncRan.add('sync') } as TransitionListener)
        })
        capture = LogCapture.start('org.transflux.execution.listener')

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new UnforkableCtx())
        sm.close()

        then: 'the transition is untouched, and the sync listener declared after it still ran'
        result.success
        syncRan.toList() == ['sync']
        asyncRan.isEmpty()
        capture.messagesAtOrAbove(Level.WARN).any {
            it.startsWith('Async listener not notified') && it.contains('listenerId=async')
        }
    }

    // ----- the machine it observes -----

    def 'an async listener may not drive the state machine that notified it, for any entity'() {
        given:
        def done = new CountDownLatch(1)
        def caught = new ConcurrentLinkedQueue<Throwable>()
        sm = build({ smd ->
            smd.onAnyTransitionComplete('driver', { l ->
                l.using({ e, c, x ->
                    try {
                        sm.entity(new Entity('s1')).transitionTo('s2')
                    } catch (Throwable ex) {
                        caught.add(ex)
                    }
                    done.countDown()
                } as TransitionListener).withAsync()
            } as Consumer)
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')

        then:
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        caught.peek() instanceof TransfluxReentrancyException
    }

    // ----- a refused submission -----

    def 'DROP loses a refused notification with a warning, and the transition succeeds'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .onAnyTransitionComplete('audit', { l ->
                   l.using({ e, c, x -> ran.add('audit') } as TransitionListener).withAsync()
               } as Consumer)
        })
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        ran.isEmpty()
        capture.messagesAtOrAbove(Level.WARN).any {
            it.startsWith('Async listener not started') && it.contains('listenerId=audit')
        }
    }

    def 'CALLER_RUNS handles a refused notification in line, still barred from driving the machine'() {
        given:
        ExecutorService hostPool = Executors.newSingleThreadExecutor()
        hostPool.shutdown()
        def ranOn = new ConcurrentLinkedQueue<String>()
        def caught = new ConcurrentLinkedQueue<Throwable>()
        sm = build({ smd ->
            smd.withAsyncExecutor(hostPool)
               .onAnyTransitionComplete('audit', { l ->
                   l.using({ e, c, x ->
                       ranOn.add(Thread.currentThread().name)
                       try {
                           sm.entity(new Entity('s1')).transitionTo('s2')
                       } catch (Throwable ex) {
                           caught.add(ex)
                       }
                   } as TransitionListener).withAsync(AsyncRejectionPolicy.CALLER_RUNS)
               } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        ranOn.toList() == [Thread.currentThread().name]
        caught.peek() instanceof TransfluxReentrancyException
    }

    def 'BLOCK after close loses the notification with a warning rather than failing the transition'() {
        given:
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncPool(1, 1)
               .onAnyTransitionComplete('audit', { l ->
                   l.using({ e, c, x -> ran.add('audit') } as TransitionListener)
                    .withAsync(AsyncRejectionPolicy.BLOCK)
               } as Consumer)
        })
        sm.close()
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        ran.isEmpty()
        capture.messagesAtOrAbove(Level.WARN).any {
            it.startsWith('Async listener not started') && it.contains('RejectedExecutionException')
        }
    }

    def 'BLOCK notified from a branch still finishing after close runs in line on that branch'() {
        given: 'a forked branch that dispatches only once the pool has been shut down'
        def branchThread = new ConcurrentLinkedQueue<String>()
        def listenerThread = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncPool(1, 1)
               .step('after', { e, c, t -> } as Action)
               .step('slow', { e, c, view ->
                   branchThread.add(Thread.currentThread().name)
                   waitFor { sm.@asyncExecutor.isShutdown() }
                   view.run('after')
               } as Action)
               .onAnyActionComplete('audit', { l ->
                   l.using({ e, c, x ->
                       if (x.actionId() == 'after') {
                           listenerThread.add(Thread.currentThread().name)
                       }
                   } as ActionListener).withAsync(AsyncRejectionPolicy.BLOCK)
               } as Consumer)
        }, { t -> t.fork('slow') })

        when: 'close waits for the running branch, which notifies against a closed pool'
        def result = sm.executeTransition(new Entity('s1'), 's2')
        sm.close()

        then: 'the branch check wins over the closed one: delivered in line, not lost'
        result.success
        listenerThread.toList() == branchThread.toList()
    }

    def 'BLOCK waits for capacity rather than losing a notification'() {
        given: 'one worker, held, and one queue slot'
        def release = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncPool(1, 1)
               .step('work', { e, c, t -> } as Action)
               .onAnyActionComplete('audit', { l ->
                   l.using({ e, c, x ->
                       release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                       ran.add(x.actionId())
                   } as ActionListener).withAsync(AsyncRejectionPolicy.BLOCK)
               } as Consumer)
        }, { t -> t.run('work').run('work').run('work') })

        when: 'the transition runs on its own thread, so the third notification can park'
        def caller = new Thread({ sm.executeTransition(new Entity('s1'), 's2') })
        caller.start()
        caller.join(300)

        then:
        caller.isAlive()

        when:
        release.countDown()
        caller.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))

        then:
        waitFor { ran.size() == 3 }
    }

    def 'an action dispatched in a loop floods the queue, and DROP loses the overflow'() {
        given: 'one worker held on the first notification, and four queue slots'
        def release = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.withAsyncPool(1, 4)
               .step('item', { e, c, t -> } as Action)
               .step('loop', { e, c, view -> 1000.times { view.run('item') } } as Action)
               .onAnyActionStart('audit', { l ->
                   l.using({ e, c, x ->
                       release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                       ran.add(x.actionId())
                   } as ActionListener).withAsync()
               } as Consumer)
        }, { t -> t.run('loop') })
        capture = LogCapture.start('org.transflux.execution.async')

        when: 'one transition submits a notification per action start: 1001 of them'
        def result = sm.executeTransition(new Entity('s1'), 's2')
        release.countDown()
        sm.close()

        then: 'the transition is untouched, and only what the pool had room for ran'
        result.success
        ran.size() == 5
        capture.messagesAtOrAbove(Level.WARN).count { it.startsWith('Async listener not started') } == 996
    }

    private StateMachine<Entity> build(Closure smConfig, Closure body = {}, Closure s2Config = {}) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        smConfig.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> body.call(t) } as Consumer)
        } as Consumer)
        builder.state('s2', { s -> s2Config.call(s) } as Consumer)
        return smd.build()
    }

    private static boolean waitFor(Closure<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true
            }
            Thread.sleep(10)
        }
        return false
    }
}

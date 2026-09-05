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
import org.transflux.core.action.OperationDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * What forking a member changes about the transition that declares it: the member leaves the
 * transition's timeline, its result, and its failure handling.
 */
class StateMachineImplForkSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    ExecutorService executor
    StateMachine<Entity> sm

    def setup() {
        executor = Executors.newSingleThreadExecutor()
    }

    def cleanup() {
        sm?.close()
        executor.shutdownNow()
    }

    def 'a forked member runs, on another thread'() {
        given:
        def done = new CountDownLatch(1)
        def branchThread = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t ->
                branchThread.add(Thread.currentThread().name)
                done.countDown()
            } as Action)
        }, { op -> op.fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        branchThread.peek() != Thread.currentThread().name
    }

    def 'the transition returns without waiting for the branch'() {
        given: 'a branch that cannot finish until the test lets it'
        def release = new CountDownLatch(1)
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('slow', { e, c, t ->
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                done.countDown()
            } as Action)
        }, { op -> op.fork('slow') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the caller already has its result while the branch is still blocked'
        result.success
        done.count == 1

        cleanup:
        release.countDown()
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    def 'members after a forked one run without waiting for it'() {
        given:
        def release = new CountDownLatch(1)
        def order = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('slow', { e, c, t ->
                release.await(WAIT_SECONDS, TimeUnit.SECONDS)
                order.add('branch')
            } as Action)
             .step('after', { e, c, t -> order.add('after') } as Action)
        }, { op -> op.fork('slow').run('after') })

        when:
        sm.executeTransition(new Entity('s1'), 's2')

        then:
        order.toList() == ['after']

        cleanup:
        release.countDown()
    }

    def 'a forked member appears on neither executed nor compensated path'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> done.countDown() } as Action)
             .step('sync', { e, c, t -> } as Action)
        }, { op -> op.run('sync').fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the transition reports what it ran, and the branch is not part of that'
        result.executedPath*.toString().any { it.endsWith('sync') }
        result.executedPath*.toString().every { !it.contains('notify') }
        result.compensatedPath.isEmpty()
    }

    def 'a branch failure leaves the transition successful'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('boom', { e, c, t ->
                try {
                    throw new IllegalStateException('branch blew up')
                } finally {
                    done.countDown()
                }
            } as Action)
        }, { op -> op.fork('boom') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        result.error == null
    }

    def 'a submitted branch still runs when the sync path fails afterwards'() {
        given: 'submission is the commitment point, so nothing un-submits it'
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> done.countDown() } as Action)
             .step('fails', { e, c, t -> throw new IllegalStateException('sync failed') } as Action)
        }, { op -> op.fork('notify').run('fails') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        !result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    def 'the action listener sees the branch under the container qualified path'() {
        given:
        def done = new CountDownLatch(1)
        def paths = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> } as Action)
             .onAnyActionComplete('watch', { e, c, execution ->
                 paths.add(execution.path().toString())
                 if (execution.path().toString().contains('notify')) {
                     done.countDown()
                 }
             } as ActionListener)
        }, { op -> op.fork('notify') })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        paths.any { it == 'op/notify' }
    }

    def 'a branch resolves run(id) against the container scope it inherited'() {
        given:
        def done = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('dispatcher', { e, c, t ->
                t.run('inline-only')
                done.countDown()
            } as Action)
        }, { op ->
            op.step('inline-only', { e, c, t -> ran.add('inline-only') } as Action)
              .fork('dispatcher')
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the inline member is visible only inside the container, and the branch is inside it'
        ran.contains('inline-only')
    }

    def 'a forked branch member runs on another thread and stays off both paths'() {
        given: 'a branch member forks exactly as a container member does'
        def done = new CountDownLatch(1)
        def branchThread = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t ->
                branchThread.add(Thread.currentThread().name)
                done.countDown()
            } as Action)
        }, { op ->
            op.conditional('route', { cs ->
                cs.branch('only', { b ->
                    b.condition('always', { e -> true } as Predicate).fork('notify')
                } as Consumer)
            } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        branchThread.size() == 1
        branchThread.first() != Thread.currentThread().name
        result.executedPath*.toString() == ['op', 'op/route']
        result.compensatedPath.isEmpty()
    }

    def 'a forked branch member is notified under the conditional qualified path'() {
        given:
        def done = new CountDownLatch(1)
        def paths = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> } as Action)
             .onAnyActionComplete('watch', { e, c, execution ->
                 paths.add(execution.path().toString())
                 if (execution.path().toString().contains('notify')) {
                     done.countDown()
                 }
             } as ActionListener)
        }, { op ->
            op.conditional('route', { cs ->
                cs.branch('only', { b ->
                    b.condition('always', { e -> true } as Predicate).fork('notify')
                } as Consumer)
            } as Consumer)
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the conditional pushed its own id, so the member qualifies beneath it'
        paths.any { it == 'op/route/notify' }
    }

    def 'a transition may fork a member directly, with no wrapping operation'() {
        given: "the slot held one action, so this needed a container that existed only to hold it"
        def done = new CountDownLatch(1)
        def branchThread = new ConcurrentLinkedQueue<String>()
        def sm = buildOnTransition({ smd ->
            smd.step('notify', { e, c, t ->
                branchThread.add(Thread.currentThread().name)
                done.countDown()
            } as Action)
        }, { t -> t.step('commit', { e, c, tr -> } as Action).fork('notify') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the transition committed without waiting, and the branch is on neither path'
        result.success
        branchThread.size() == 1
        branchThread.first() != Thread.currentThread().name
        result.executedPath*.toString() == ['commit']
        result.compensatedPath.isEmpty()
    }

    def 'a definition whose only fork sits on a transition still gets an executor'() {
        given: "definitionForks walks the transition's body, which is where the member now lives"
        def done = new CountDownLatch(1)
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .step('notify', { e, c, t -> done.countDown() } as Action)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', { t -> t.fork('notify') }) })
        smd.state('s2', {})

        when: 'no executor is supplied, so the framework must have built a pool of its own'
        def sm = smd.build()
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        cleanup:
        sm?.close()
    }

    def 'an inline forked step runs on another thread and stays off both paths'() {
        given: 'a one-off notification, declared where it runs rather than registered elsewhere'
        def done = new CountDownLatch(1)
        def branchThread = new ConcurrentLinkedQueue<String>()
        sm = build({ smd -> }, { op ->
            op.step('commit', { e, c, t -> } as Action)
                .forkStep('notify', { e, c, t ->
                    branchThread.add(Thread.currentThread().name)
                    done.countDown()
                } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        branchThread.first() != Thread.currentThread().name
        result.executedPath*.toString() == ['op', 'op/commit']
        result.compensatedPath.isEmpty()
    }

    def 'an inline forked operation runs its whole member list, in order, on the branch'() {
        given:
        def done = new CountDownLatch(2)
        def order = new ConcurrentLinkedQueue<String>()
        sm = build({ smd -> }, { op ->
            op.forkOperation('group', { OperationDef<Entity, Object> inner ->
                inner.step('first', { e, c, t -> order.add('first'); done.countDown() } as Action)
                    .step('second', { e, c, t -> order.add('second'); done.countDown() } as Action)
            } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the group left the transition whole, and kept its declaration order once there'
        result.success
        result.executedPath*.toString() == ['op']
        order.toList() == ['first', 'second']
    }

    def 'an inline forked conditional selects a branch on the branch'() {
        given:
        def done = new CountDownLatch(1)
        def taken = new ConcurrentLinkedQueue<String>()
        sm = build({ smd -> }, { op ->
            op.forkConditional('route', { cs ->
                cs.branch('critical', { b ->
                    b.condition('always', { e -> true } as Predicate)
                        .step('escalate', { e, c, t ->
                            taken.add('critical')
                            done.countDown()
                        } as Action)
                } as Consumer)
                    .branch('routine', { b ->
                        b.condition('never', { e -> false } as Predicate)
                            .step('ignore', { e, c, t -> taken.add('routine') } as Action)
                    } as Consumer)
            } as Consumer)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        result.success
        result.executedPath*.toString() == ['op']
        taken.toList() == ['critical']
    }

    private StateMachine<Entity> buildOnTransition(Closure registrations, Closure members) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .withAsyncExecutor(executor)
        registrations.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> members.call(t) } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }

    private StateMachine<Entity> build(Closure registrations, Closure members) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .withAsyncExecutor(executor)
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

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
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * A forked member owns its rollback: its stack unwinds alone, and neither direction of failure
 * reaches across the boundary.
 */
class StateMachineImplForkCompensationSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class TimeoutException extends RuntimeException {
        TimeoutException(String message) {
            super(message)
        }
    }

    ExecutorService executor
    StateMachine<Entity> sm
    ConcurrentLinkedQueue<String> trail

    def setup() {
        executor = Executors.newSingleThreadExecutor()
        trail = new ConcurrentLinkedQueue<String>()
    }

    def cleanup() {
        sm?.close()
        executor.shutdownNow()
    }

    def 'a failing branch drains its own stack in LIFO order'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.operation('inner', Object, { OperationDef<Entity, Object> op ->
                op.step('first', { StepDef<Entity, Object> s ->
                    s.using({ e, c, t -> trail.add('first') } as Action)
                     .withCompensation({ e, c -> trail.add('-first') } as Compensation)
                } as Consumer)
                  .step('second', { StepDef<Entity, Object> s ->
                      s.using({ e, c, t -> trail.add('second') } as Action)
                       .withCompensation({ e, c -> trail.add('-second') } as Compensation)
                  } as Consumer)
                  .step('boom', { StepDef<Entity, Object> s ->
                      s.using({ e, c, t ->
                          try {
                              throw new IllegalStateException('branch failed')
                          } finally {
                              done.countDown()
                          }
                      } as Action)
                  } as Consumer)
            } as Consumer)
        }, { op -> op.fork('inner') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        Thread.sleep(200)

        then: 'members unwind last-in first-out, inside the branch only'
        trail.toList() == ['first', 'second', '-second', '-first']

        and: 'the transition itself succeeded and rolled nothing back'
        result.success
        result.compensatedPath.isEmpty()
    }

    def 'a sync failure drains only the sync stack'() {
        given:
        def branchDone = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('forked', { StepDef<Entity, Object> s ->
                s.using({ e, c, t -> trail.add('forked'); branchDone.countDown() } as Action)
                 .withCompensation({ e, c -> trail.add('-forked') } as Compensation)
            } as Consumer)
             .step('sync', { StepDef<Entity, Object> s ->
                 s.using({ e, c, t -> trail.add('sync') } as Action)
                  .withCompensation({ e, c -> trail.add('-sync') } as Compensation)
             } as Consumer)
             .step('fails', { e, c, t -> throw new IllegalStateException('sync failed') } as Action)
        }, { op -> op.run('sync').fork('forked').run('fails') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        branchDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
        Thread.sleep(200)

        then: 'the branch completed and keeps its compensation; only the sync member rolled back'
        !result.success
        result.compensatedPath*.toString() == ['op/sync']
        trail.contains('-sync')
        !trail.contains('-forked')
    }

    def 'exception routing applies inside a branch'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('routed', { StepDef<Entity, Object> s ->
                s.using({ e, c, t ->
                    try {
                        throw new TimeoutException('gateway')
                    } finally {
                        done.countDown()
                    }
                } as Action)
                 .withCompensation({ e, c -> trail.add('-fallback') } as Compensation)
                 .forException(TimeoutException)
                 .withCompensation({ e, c -> trail.add('-routed') } as Compensation)
            } as Consumer)
        }, { op -> op.fork('routed') })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        Thread.sleep(200)

        then:
        trail.toList() == ['-routed']
    }

    def 'a branch whose routes all miss rolls nothing back'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('routed', { StepDef<Entity, Object> s ->
                s.using({ e, c, t ->
                    try {
                        throw new IllegalStateException('not a timeout')
                    } finally {
                        done.countDown()
                    }
                } as Action)
                 .forException(TimeoutException)
                 .withCompensation({ e, c -> trail.add('-routed') } as Compensation)
            } as Consumer)
        }, { op -> op.fork('routed') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        Thread.sleep(200)

        then:
        trail.isEmpty()
        result.success
    }

    def 'a forked branch member owns its rollback, and the sync path keeps its own'() {
        given: 'the fork is declared at a branch position rather than a container one'
        def branchDone = new CountDownLatch(1)
        sm = build({ smd ->
            smd.operation('inner', Object, { OperationDef<Entity, Object> op ->
                op.step('branch-work', { StepDef<Entity, Object> s ->
                    s.using({ e, c, t -> trail.add('branch-work') } as Action)
                     .withCompensation({ e, c -> trail.add('-branch-work') } as Compensation)
                } as Consumer)
                  .step('branch-boom', { StepDef<Entity, Object> s ->
                      s.using({ e, c, t ->
                          try {
                              throw new IllegalStateException('branch failed')
                          } finally {
                              branchDone.countDown()
                          }
                      } as Action)
                  } as Consumer)
            } as Consumer)
             .step('sync', { StepDef<Entity, Object> s ->
                 s.using({ e, c, t -> trail.add('sync') } as Action)
                  .withCompensation({ e, c -> trail.add('-sync') } as Compensation)
             } as Consumer)
        }, { op ->
            op.conditional('route', { cs ->
                cs.branch('only', { b ->
                    b.condition('always', { e -> true } as Predicate).fork('inner')
                } as Consumer)
            } as Consumer)
              .run('sync')
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        branchDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
        Thread.sleep(200)

        then: 'the branch unwound only what it ran, and the transition rolled back nothing'
        trail.toList().containsAll(['branch-work', '-branch-work', 'sync'])
        !trail.contains('-sync')

        and:
        result.success
        result.compensatedPath.isEmpty()
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

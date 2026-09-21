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
import org.transflux.core.action.OperationDef
import org.transflux.core.exception.TransfluxReentrancyException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * A forked member may not drive the state machine that forked it - for any entity. Its outcome
 * reaches nobody, so a transition it ran would report success or failure to nobody either.
 */
class StateMachineImplForkReentrancySpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    ExecutorService executor
    StateMachine<Entity> sm
    StateMachine<Entity> other

    def setup() {
        executor = Executors.newSingleThreadExecutor()
    }

    def cleanup() {
        sm?.close()
        other?.close()
        executor.shutdownNow()
    }

    @Unroll
    def 'a branch calling #entry on the machine that forked it is rejected'() {
        given:
        def done = new CountDownLatch(1)
        def caught = new AtomicReference<Throwable>()
        def handle = new AtomicReference<StateMachine<Entity>>()

        sm = build({ smd ->
            smd.step('reentrant', { e, c, t ->
                try {
                    call.call(handle.get(), e)
                } catch (Throwable thrown) {
                    caught.set(thrown)
                } finally {
                    done.countDown()
                }
            } as Action)
        }, { op -> op.fork('reentrant') })
        handle.set(sm)

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        caught.get() instanceof TransfluxReentrancyException
        caught.get().message.contains('async branch')

        where:
        entry               || call
        'transitionTo'      || { machine, e -> machine.entity(new Entity('s1')).transitionTo('s2') }
        'executeTransition' || { machine, e -> machine.executeTransition(new Entity('s1'), 's2') }
        'fire'              || { machine, e -> machine.entity(new Entity('s1')).fire('trigger') }
        'processEvent'      || { machine, e -> machine.entity(new Entity('s1')).processEvent('evt', null) }
        'processDataChange' || { machine, e -> machine.entity(new Entity('s1')).processDataChange() }
    }

    def 'the ban covers a different entity too'() {
        given:
        def done = new CountDownLatch(1)
        def caught = new AtomicReference<Throwable>()
        def handle = new AtomicReference<StateMachine<Entity>>()

        sm = build({ smd ->
            smd.step('reentrant', { e, c, t ->
                try {
                    handle.get().executeTransition(new Entity('s1'), 's2')
                } catch (Throwable thrown) {
                    caught.set(thrown)
                } finally {
                    done.countDown()
                }
            } as Action)
        }, { op -> op.fork('reentrant') })
        handle.set(sm)

        when: 'the entity under transition and the one the branch drives are different objects'
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then:
        caught.get() instanceof TransfluxReentrancyException
    }

    def 'a different state machine is drivable from inside a branch'() {
        given:
        def done = new CountDownLatch(1)
        def outcome = new AtomicReference<Boolean>()
        def handle = new AtomicReference<StateMachine<Entity>>()

        sm = build({ smd ->
            smd.step('drives-other', { e, c, t ->
                try {
                    outcome.set(handle.get().executeTransition(new Entity('s1'), 's2').success)
                } finally {
                    done.countDown()
                }
            } as Action)
        }, { op -> op.fork('drives-other') })
        other = build({ smd -> smd.step('noop', { e, c, t -> } as Action) }, { op -> op.run('noop') })
        handle.set(other)

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'only the machine that forked the branch is closed to it'
        outcome.get()
    }

    def 'the mark is gone once the branch returns, so a pooled thread is left clean'() {
        given:
        def branchDone = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('quiet', { e, c, t -> branchDone.countDown() } as Action)
        }, { op -> op.fork('quiet') })

        when: 'the same pool thread is reused for an ordinary host call afterwards'
        sm.executeTransition(new Entity('s1'), 's2')
        branchDone.await(WAIT_SECONDS, TimeUnit.SECONDS)
        def later = executor.submit({ -> sm.executeTransition(new Entity('s1'), 's2') } as java.util.concurrent.Callable)

        then:
        later.get(WAIT_SECONDS, TimeUnit.SECONDS).success
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

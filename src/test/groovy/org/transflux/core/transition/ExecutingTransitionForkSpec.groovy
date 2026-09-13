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

package org.transflux.core.transition

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.ActionListener
import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.ForkableContext
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Forking from inside an action's body: the branch behaves as a declared forked member does, and
 * the differences are the ones the build cannot see — where the executor comes from, and which
 * policy answers for a refused submission.
 */
class ExecutingTransitionForkSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentCtx {
        String input
    }

    static class ChildCtx {
        String input
    }

    static class CountingForkCtx implements ForkableContext<CountingForkCtx> {
        int forks

        @Override
        CountingForkCtx fork() {
            forks++
            return new CountingForkCtx()
        }
    }

    ExecutorService executor
    StateMachine<Entity> sm

    def setup() {
        executor = Executors.newSingleThreadExecutor()
    }

    def cleanup() {
        sm?.close()
        executor?.shutdownNow()
    }

    // ----- the three call shapes -----

    def 'view.fork(id) runs the action on another thread'() {
        given:
        def done = new CountDownLatch(1)
        def threads = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('notify', { e, c, t ->
                threads.add(Thread.currentThread().name)
                done.countDown()
            } as Action)
        }, { t ->
            t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        threads.peek() != Thread.currentThread().name
    }

    def 'view.fork(id, mapperId) hands the branch the mapped context'() {
        given:
        def done = new CountDownLatch(1)
        def seen = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('child', ChildCtx, { e, ChildCtx c, t ->
                seen.add(c.input)
                done.countDown()
            } as Action)
            smd.mapper('child-from-parent', ParentCtx, ChildCtx,
                       { ParentCtx p -> new ChildCtx(input: p.input) } as ContextMapper)
        }, { t ->
            t.step('dispatch', { e, c, view -> view.fork('child', 'child-from-parent') } as Action)
        })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'by-id'))

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        seen.toList() == ['by-id']
    }

    def 'view.fork(id, inlineMapper) hands the branch the projected context'() {
        given:
        def done = new CountDownLatch(1)
        def seen = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('child', ChildCtx, { e, ChildCtx c, t ->
                seen.add(c.input)
                done.countDown()
            } as Action)
        }, { t ->
            t.step('dispatch', { e, ParentCtx c, view ->
                view.fork('child', { ParentCtx p -> new ChildCtx(input: p.input) } as ContextMapper)
            } as Action)
        })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'inline'))

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        seen.toList() == ['inline']
    }

    // ----- what the branch does not touch -----

    def 'a branch forked from a body reaches neither reported path, and its failure stays there'() {
        given:
        def done = new CountDownLatch(1)
        sm = build({ smd ->
            smd.step('notify', { e, c, t ->
                done.countDown()
                throw new IllegalStateException('branch blew up')
            } as Action)
        }, { t ->
            t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the transition reports the body that dispatched it and nothing of the branch'
        result.success
        result.executedPath*.toString() == ['dispatch']
        result.compensatedPath.isEmpty()
    }

    def 'the branch path nests under the action whose body forked it'() {
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
        }, { t ->
            t.operation('op', { OperationDef<Entity, Object> op ->
                op.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
            } as Consumer)
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'it inherits the body position, not the transition root'
        paths.any { it == 'op/dispatch/notify' }
    }

    def 'a branch forked from a body resolves run(id) against the scope the body had'() {
        given:
        def done = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('forked', { e, c, t ->
                t.run('inline-only')
                done.countDown()
            } as Action)
        }, { t ->
            t.operation('op', { OperationDef<Entity, Object> op ->
                op.step('inline-only', { e, c, t2 -> ran.add('inline-only') } as Action)
                  .step('dispatch', { e, c, view -> view.fork('forked') } as Action)
            } as Consumer)
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the container id is visible only inside it, and the branch inherited that scope'
        ran.contains('inline-only')
    }

    def 'a body already running on a branch may fork again'() {
        given: 'the ban stops a branch driving the machine, not spawning more work'
        def done = new CountDownLatch(1)
        def ran = new ConcurrentLinkedQueue<String>()
        sm = build({ smd ->
            smd.step('inner', { e, c, t ->
                ran.add('inner')
                done.countDown()
            } as Action)
               .step('outer', { e, c, view ->
                   ran.add('outer')
                   view.fork('inner')
               } as Action)
        }, { t -> t.fork('outer') })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
        ran.containsAll(['outer', 'inner'])
    }

    // ----- what only a body-level fork has to answer for -----

    def 'a fork the build could not see needs an executor the definition asked for'() {
        given: 'nothing in this definition declares a forked member, so nothing built a pool'
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        builder.step('notify', { e, c, t -> } as Action)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        sm = smd.build()

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the failure names the two ways to ask for one'
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.contains('withAsyncPool')
        result.error.message.contains('withAsyncExecutor')
    }

    def 'a fork refused for want of an executor never asks the context to copy itself'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        builder.step('notify', { e, c, t -> } as Action)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        sm = smd.build()
        def ctx = new CountingForkCtx()

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', ctx)

        then:
        !result.success
        result.error.message.contains('withAsyncPool')
        ctx.forks == 0
    }

    def 'declaring a pool is enough to get one, though nothing declares a fork'() {
        given:
        def done = new CountDownLatch(1)
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .withAsyncPool(1, 4)
        builder.step('notify', { e, c, t -> done.countDown() } as Action)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        sm = smd.build()

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        result.success
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)
    }

    def 'BLOCK chosen in a body is refused at the submission against a host executor'() {
        given: 'the build rejects this wherever it can see it, and cannot see it here'
        sm = build({ smd ->
            smd.step('notify', { e, c, t -> } as Action)
        }, { t ->
            t.step('dispatch', { e, c, view ->
                view.fork('notify', AsyncRejectionPolicy.BLOCK)
            } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then:
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.contains('BLOCK')
        result.error.message.contains('notify')
    }

    def 'the policy at the call site beats the one on the action def'() {
        given: 'a host executor that refuses everything, so every submission takes the policy'
        executor.shutdown()
        sm = build({ smd ->
            smd.step('notify', Object, { StepDef<Entity, Object> st ->
                st.using({ e, c, t -> } as Action)
                  .withAsyncRejectionPolicy(AsyncRejectionPolicy.FAIL)
            } as Consumer)
        }, { t ->
            t.step('dispatch', { e, c, view ->
                view.fork('notify', AsyncRejectionPolicy.DROP)
            } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'DROP at the call site loses the branch instead of failing the transition'
        result.success
    }

    def 'the policy on the action def beats the machine default'() {
        given:
        executor.shutdown()
        sm = build({ smd ->
            smd.withAsyncRejectionPolicy(AsyncRejectionPolicy.DROP)
            smd.step('notify', Object, { StepDef<Entity, Object> st ->
                st.using({ e, c, t -> } as Action)
                  .withAsyncRejectionPolicy(AsyncRejectionPolicy.FAIL)
            } as Consumer)
        }, { t ->
            t.step('dispatch', { e, c, view -> view.fork('notify') } as Action)
        })

        when:
        def result = sm.executeTransition(new Entity('s1'), 's2')

        then: 'the def said this work must not be lost, and the body said nothing'
        !result.success
    }

    // ----- the boundary the call site is the only place to check -----

    def 'a mapper producing a context the callee cannot take fails at the fork site'() {
        given:
        sm = build({ smd ->
            smd.step('child', ChildCtx, { e, c, t -> } as Action)
        }, { t ->
            t.step('dispatch', { e, c, view ->
                view.fork('child', { p -> 'not a context' } as ContextMapper)
            } as Action)
        })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'x'))

        then: 'the transition fails here rather than the branch failing on a worker'
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.contains('child')
        result.error.message.contains(ChildCtx.name)
    }

    def 'a mapper producing null at a fork site is refused'() {
        given:
        sm = build({ smd ->
            smd.step('child', ChildCtx, { e, c, t -> } as Action)
        }, { t ->
            t.step('dispatch', { e, c, view ->
                view.fork('child', { p -> null } as ContextMapper)
            } as Action)
        })

        when:
        def result = sm.entity(new Entity('s1')).transitionTo('s2', new ParentCtx(input: 'x'))

        then:
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message.contains('produced null')
    }

    private StateMachine<Entity> build(Closure registrations, Closure body) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .withAsyncExecutor(executor)
        registrations.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> body.call(t) } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd.build()
    }
}

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
import org.transflux.core.exception.TransfluxReentrancyException
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

/**
 * Replacing the definition a state machine runs: what the swap guarantees, what it refuses, and
 * what it leaves alone.
 */
class StateMachineImplReplaceDefinitionSpec extends Specification {

    static final long WAIT_SECONDS = 5

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class SubEntity extends Entity {
        SubEntity(String state) {
            super(state)
        }
    }

    static class Unrelated {
        String state
    }

    /** A definition whose build parks until released, holding the swap lock while it does. */
    static class ParkingDef extends StateMachineDefImpl<Entity> {
        final CountDownLatch building = new CountDownLatch(1)
        final CountDownLatch release = new CountDownLatch(1)

        @Override
        StateMachineSnapshot<Entity> buildSnapshot(StateMachineImpl<Entity> handle, long generation) {
            building.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return super.buildSnapshot(handle, generation)
        }
    }

    StateMachine<Entity> sm
    ExecutorService hostExecutor
    LogCapture capture

    def cleanup() {
        capture?.stop()
        sm?.close()
        hostExecutor?.shutdownNow()
    }

    // --- generation ---------------------------------------------------------

    def 'a built state machine starts at generation 1'() {
        when:
        sm = definition('v1', []).build()

        then:
        sm.generation() == 1
    }

    def 'each successful replacement returns the next generation and adds exactly one'() {
        given:
        def trail = []
        sm = definition('v1', trail).build()

        expect:
        sm.replaceDefinition(definition('v2', trail)) == 2
        sm.replaceDefinition(definition('v3', trail)) == 3
        sm.generation() == 3
    }

    def 'a rejected replacement leaves the generation and the behaviour untouched'() {
        given:
        def trail = []
        sm = definition('v1', trail).build()

        and: 'a definition that cannot build - a member naming an action nothing declares'
        def broken = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = accessors(broken)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t -> t.run('nothing-declares-this') } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)

        when:
        sm.replaceDefinition(broken)

        then:
        thrown(TransfluxValidationException)
        sm.generation() == 1

        and: 'the definition in force still runs'
        sm.entity(new Entity('s1')).transitionTo('s2').success
        trail == ['v1']
    }

    // --- what the swap installs --------------------------------------------

    def 'the new definition is what runs, metadata included'() {
        given:
        def trail = []
        sm = definition('v1', trail).build()
        sm.entity(new Entity('s1')).transitionTo('s2')

        when:
        sm.replaceDefinition(definition('v2', trail))
        def entity = new Entity('s1')
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        entity.state == 's2'
        trail == ['v1', 'v2']
        sm.getVersion() == 'v2'
    }

    def 'a topology the new definition dropped is gone'() {
        given:
        def trail = []
        sm = definition('v1', trail).build()

        when: 'the replacement routes s1 somewhere else entirely'
        def rerouted = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = accessors(rerouted)
        builder.state('s1', { s -> s.transitionsTo('s3', 't-other', {} as Consumer) } as Consumer)
        builder.state('s3', {} as Consumer)
        sm.replaceDefinition(rerouted)
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('s2')
    }

    // --- entity type --------------------------------------------------------

    def 'only the same entity class may replace the definition - #kind'() {
        given:
        sm = definition('v1', []).build()

        when:
        sm.replaceDefinition(typed(entityType))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains(Entity.name)
        e.message.contains(entityType.name)
        sm.generation() == 1

        where:
        kind          || entityType
        'a subtype'   || SubEntity
        'a supertype' || Object
        'unrelated'   || Unrelated
    }

    def 'a definition built elsewhere is not a definition this state machine can install'() {
        given:
        sm = definition('v1', []).build()

        when:
        sm.replaceDefinition(Stub(StateMachineDef))

        then:
        thrown(TransfluxValidationException)
        sm.generation() == 1
    }

    def 'a null definition is refused'() {
        given:
        sm = definition('v1', []).build()

        when:
        sm.replaceDefinition(null)

        then:
        thrown(TransfluxValidationException)
    }

    // --- in-flight isolation ------------------------------------------------

    def 'a transition in flight finishes against the definition it started under'() {
        given: 'a step that parks until the swap has happened'
        def trail = []
        def parked = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        sm = definition('v1', trail, {
            parked.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }).build()

        and:
        hostExecutor = Executors.newSingleThreadExecutor()
        def outcome = hostExecutor.submit(
            { sm.entity(new Entity('s1')).transitionTo('s2') } as Callable)
        parked.await(WAIT_SECONDS, TimeUnit.SECONDS)

        when: 'two replacements land while it is parked'
        sm.replaceDefinition(definition('v2', trail))
        sm.replaceDefinition(definition('v3', trail))
        release.countDown()

        then: 'it completes, and it ran generation 1\'s step'
        outcome.get(WAIT_SECONDS, TimeUnit.SECONDS).success
        trail == ['v1']
        sm.generation() == 3
    }

    def 'an entity binding runs against the definition it captured'() {
        given:
        def trail = []
        sm = definition('v1', trail).build()
        def binding = sm.entity(new Entity('s1'))

        when:
        sm.replaceDefinition(definition('v2', trail))
        binding.transitionTo('s2')

        then:
        trail == ['v1']
    }

    // --- the guards ---------------------------------------------------------

    def 'reentering through the handle is rejected across a replacement'() {
        given: 'a step that swaps the definition and then calls back in for the same entity'
        def trail = []
        def entity = new Entity('s1')
        def reentrant = new AtomicReference()
        sm = definition('v1', trail, {
            sm.replaceDefinition(definition('v2', trail))
            try {
                sm.entity(entity).transitionTo('s2')
            } catch (Throwable failure) {
                reentrant.set(failure)
            }
        }).build()

        when:
        sm.entity(entity).transitionTo('s2')

        then: 'the guard keys on the state machine, not on the version the execution started under'
        sm.generation() == 2
        reentrant.get() instanceof TransfluxReentrancyException
    }

    def 'a branch forked under one generation may not drive the next'() {
        given:
        def trail = []
        def rejected = new ConcurrentLinkedQueue()
        def swapped = new CountDownLatch(1)
        def done = new CountDownLatch(1)
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = accessors(smd)
        builder.withAsyncPool(1, 4)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.forkStep('branch', { e, c, tr ->
                    swapped.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    try {
                        sm.entity(new Entity('s1')).transitionTo('s2')
                    } catch (Throwable failure) {
                        rejected.add(failure)
                    } finally {
                        done.countDown()
                    }
                } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        sm = smd.build()

        when:
        sm.entity(new Entity('s1')).transitionTo('s2')
        sm.replaceDefinition(definition('v2', trail))
        swapped.countDown()
        done.await(WAIT_SECONDS, TimeUnit.SECONDS)

        then: 'the ban is on the state machine, so a later generation is closed to the branch too'
        done.count == 0
        rejected.size() == 1
        rejected.peek() instanceof TransfluxReentrancyException
    }

    // --- the executor -------------------------------------------------------

    def 'a definition that needs an executor gets one at the swap'() {
        given: 'generation 1 forks nothing, so no pool was built'
        capture = LogCapture.start('org.transflux.execution.async')
        sm = definition('v1', []).build()

        and:
        def forked = new CountDownLatch(1)

        when:
        sm.replaceDefinition(forking(forked, { builder -> builder.withAsyncPool(1, 4) }))
        sm.entity(new Entity('s1')).transitionTo('s2')

        then:
        forked.await(WAIT_SECONDS, TimeUnit.SECONDS)
        capture.messages().any { it.contains('Async pool created') }
    }

    def 'a later definition does not re-configure the executor, and says so'() {
        given:
        def forked = new CountDownLatch(1)
        sm = forking(forked, { builder -> builder.withAsyncPool(1, 4) }).build()
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(8, 64) }))

        then:
        def warnings = capture.messagesAtOrAbove(Level.WARN)
        warnings.size() == 1
        warnings[0].contains('declaredThreads=8')
        warnings[0].contains('threads=1')
    }

    def 'a later definition declaring the same pool size says nothing'() {
        given:
        sm = forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }).build()
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }))

        then:
        capture.messagesAtOrAbove(Level.WARN).isEmpty()
    }

    def 'a host executor in force is not replaced, and is not closed'() {
        given:
        hostExecutor = Executors.newSingleThreadExecutor()
        sm = forking(new CountDownLatch(1), { builder -> builder.withAsyncExecutor(hostExecutor) }).build()
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(2, 8) }))
        sm.close()

        then:
        def warnings = capture.messagesAtOrAbove(Level.WARN)
        warnings.size() == 1
        warnings[0].contains('host-supplied')
        !hostExecutor.isShutdown()
    }

    def 'a replacement asking to block against a host executor is refused'() {
        given:
        hostExecutor = Executors.newSingleThreadExecutor()
        sm = forking(new CountDownLatch(1), { builder -> builder.withAsyncExecutor(hostExecutor) }).build()

        when:
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder ->
            builder.withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
        }))

        then:
        thrown(TransfluxValidationException)
        sm.generation() == 1
    }

    def 'a closed state machine refuses a replacement'() {
        given:
        sm = definition('v1', []).build()
        sm.close()

        when: 'a definition installed here would have no executor to run its async work'
        sm.replaceDefinition(definition('v2', []))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('closed')
        sm.generation() == 1
    }

    def 'a later definition introducing BLOCK on an unfair pool is reported'() {
        given: 'generation 1 declares no BLOCK, so its queue was not built fair'
        sm = forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }).build()
        capture = LogCapture.start('org.transflux.execution.async')

        when:
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder ->
            builder.withAsyncRejectionPolicy(AsyncRejectionPolicy.BLOCK)
        }))

        then:
        def warnings = capture.messagesAtOrAbove(Level.WARN)
        warnings.size() == 1
        warnings[0].contains('was not built fair')
    }

    def 'a close racing a replacement waits for it, and shuts down the pool it adopted'() {
        given: 'generation 1 forks nothing, so no executor is running'
        sm = definition('v1', []).build()
        capture = LogCapture.start('org.transflux.execution.async')
        def parking = new ParkingDef()
        forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }, parking)

        and: 'a replacement parked mid-build'
        def swap = new Thread({ sm.replaceDefinition(parking) })
        swap.start()
        parking.building.await(WAIT_SECONDS, TimeUnit.SECONDS)

        when: 'close arrives while the replacement is still building'
        def closer = new Thread({ sm.close() })
        closer.start()
        waitUntil { closer.state in [Thread.State.BLOCKED, Thread.State.TERMINATED] }
        parking.release.countDown()
        swap.join(WAIT_SECONDS * 1000)
        closer.join(WAIT_SECONDS * 1000)

        then: 'the replacement landed, and the pool it adopted did not outlive the close'
        sm.generation() == 2
        capture.messages().any { it.contains('Async pool created') }
        capture.messages().any { it.contains('Async pool shut down') }
    }

    def 'closing after a replacement shuts the one pool down'() {
        given:
        capture = LogCapture.start('org.transflux.execution.async')
        sm = forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }).build()
        sm.replaceDefinition(forking(new CountDownLatch(1), { builder -> builder.withAsyncPool(1, 4) }))

        when:
        sm.close()
        sm.close()

        then:
        capture.messages().count { it.contains('Async pool shut down') } == 1
    }

    // --- helpers ------------------------------------------------------------

    private static StateMachineDefImpl<Entity> definition(String tag, List trail, Closure body = null) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = accessors(smd)
        builder.withVersion(tag)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.step('mark', { e, c, tr ->
                    trail << tag
                    body?.call()
                } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd
    }

    private static StateMachineDefImpl<Entity> forking(
        CountDownLatch done, Closure asyncConfig, StateMachineDefImpl<Entity> smd = new StateMachineDefImpl<>()) {
        StateMachineDef<Entity> builder = accessors(smd)
        asyncConfig.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', { t ->
                t.forkStep('branch', { e, c, tr -> done.countDown() } as Action)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        return smd
    }

    private static void waitUntil(Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + WAIT_SECONDS * 1000
        while (!condition.call() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    private static StateMachineDefImpl typed(Class entityType) {
        def smd = new StateMachineDefImpl()
        smd.forEntityType(entityType)
           .withStateResolver({ e -> 's1' } as StateResolver)
           .state('s1', {} as Consumer)
        return smd
    }

    private static StateMachineDef<Entity> accessors(StateMachineDefImpl<Entity> smd) {
        return smd.forEntityType(Entity)
                  .withStateResolver({ e -> e.state } as StateResolver<Entity>)
                  .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
    }
}

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
import org.transflux.core.TestContext
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateChange
import org.transflux.core.state.StateListener
import org.transflux.core.state.StatePhase
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplStateListenerSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class CountingListener implements StateListener<Entity> {
        static int instances = 0

        CountingListener() {
            instances++
        }

        @Override
        void onState(Entity entity, Object context, StateChange<Entity> change) {
        }
    }

    def 'a successful transition notifies the source exit hook and the target entry hook'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave-s1', recorder(log, 'exit-s1'))
                .transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st.onEntry('enter-s2', recorder(log, 'entry-s2')) }) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['exit-s1', 'entry-s2']
    }

    def 'the exit hook runs before the operation and the entry hook after it'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', recorder(log, 'exit'))
                .transitionsTo('s2', 't', { t -> t.step('op', { e, ctx, tr -> log << 'operation' } as Action) }) })
            .state('s2', { st -> st.onEntry('enter', recorder(log, 'entry')) }) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['exit', 'operation', 'entry']
    }

    def 'the exit hook sees the pre-transition state and the entry hook the committed one'() {
        given:
        def seen = [:]
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', { e, ctx, ch -> seen.exit = e.state } as StateListener)
                .transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st
                .onEntry('enter', { e, ctx, ch -> seen.entry = e.state } as StateListener) }) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        seen.exit == 's1'
        seen.entry == 's2'
    }

    def "per-state listeners run before global ones, each group in declaration order"() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('own-exit-1', recorder(log, 'own-exit-1'))
                .onExit('own-exit-2', recorder(log, 'own-exit-2'))
                .transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st
                .onEntry('own-entry-1', recorder(log, 'own-entry-1'))
                .onEntry('own-entry-2', recorder(log, 'own-entry-2')) })
            .onAnyStateExit('any-exit-1', recorder(log, 'any-exit-1'))
            .onAnyStateExit('any-exit-2', recorder(log, 'any-exit-2'))
            .onAnyStateEntry('any-entry-1', recorder(log, 'any-entry-1'))
            .onAnyStateEntry('any-entry-2', recorder(log, 'any-entry-2')) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['own-exit-1', 'own-exit-2', 'any-exit-1', 'any-exit-2',
                'own-entry-1', 'own-entry-2', 'any-entry-1', 'any-entry-2']
    }

    def 'a global listener fires for a state that declares none of its own'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateExit('any-exit', recorder(log, 'any-exit'))
            .onAnyStateEntry('any-entry', recorder(log, 'any-entry')) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['any-exit', 'any-entry']
    }

    def 'a class-form global listener is instantiated once, not once per state'() {
        given:
        CountingListener.instances = 0

        when:
        build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .state('s3', {})
            .onAnyStateEntry('counted', CountingListener) })

        then:
        CountingListener.instances == 1
    }

    def 'a transition rejected by a pre-condition notifies neither hook'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', recorder(log, 'exit'))
                .transitionsTo('s2', 't', { t -> t.preCondition('never', { e -> false } as Predicate) }) })
            .state('s2', { st -> st.onEntry('enter', recorder(log, 'entry')) }) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        log.isEmpty()
        entity.state == 's1'
    }

    @Unroll
    def 'a transition that fails at #stage leaves the exit hook fired and the entry hook silent'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', recorder(log, 'exit'))
                .transitionsTo('s2', 't', configure) })
            .state('s2', { st -> st.onEntry('enter', recorder(log, 'entry')) }) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        log == ['exit']
        entity.state == 's1'

        where:
        stage            | configure
        'the operation'  | { t -> t.step('op', { e, ctx, tr -> throw new IllegalStateException('boom') } as Action) }
        'a post-condition' | { t -> t.postCondition('never', { e -> false } as Predicate) }
    }

    @Unroll
    def 'a throwing #hook listener leaves the transition successful'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', exploding(hook == 'exit'))
                .transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st.onEntry('enter', exploding(hook == 'entry')) }) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        result.error == null
        result.compensatedPath.isEmpty()
        entity.state == 's2'

        where:
        hook << ['exit', 'entry']
    }

    def 'a throwing listener does not suppress the ones declared after it'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('boom', { e, ctx, ch -> throw new IllegalStateException('boom') } as StateListener)
                .onExit('after', recorder(log, 'after'))
                .transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateExit('global-after', recorder(log, 'global-after')) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['after', 'global-after']
    }

    def 'the change carries the phase, the state, and the transition'() {
        given:
        def changes = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .withName('First')
                .transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateExit('capture-exit', { e, ctx, ch -> changes << ch } as StateListener)
            .onAnyStateEntry('capture-entry', { e, ctx, ch -> changes << ch } as StateListener) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        changes.size() == 2

        and:
        StateChange exit = changes[0]
        exit.phase() == StatePhase.EXIT
        exit.state().id == 's1'
        exit.state().name == 'First'
        exit.transition().id == 't'
        exit.transition().sourceStateId == 's1'
        exit.transition().targetStateId == 's2'

        and:
        StateChange entry = changes[1]
        entry.phase() == StatePhase.ENTRY
        entry.state().id == 's2'
        entry.transition().id == 't'
    }

    def 'the transition handed to a listener refuses to dispatch actions'() {
        given:
        def failures = []
        def stepRuns = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .step('side-effect', { e, ctx, tr -> stepRuns << 'ran' } as org.transflux.core.action.Action)
            .state('s1', { st -> st
                .onExit('meddler', { e, ctx, ch ->
                    try {
                        ch.transition().run('side-effect')
                    } catch (Exception ex) {
                        failures << ex.message
                    }
                } as StateListener)
                .transitionsTo('s2', 't', {}) })
            .state('s2', {}) })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        stepRuns.isEmpty()
        failures.size() == 1
        failures.first().contains('read-only topology view')
    }

    def 'the entry hook fires even when no state applier is configured'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
           .withStateResolver({ e -> e.state } as StateResolver<Entity>)
           .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
           .state('s2', { st -> st.onEntry('enter', recorder(log, 'entry')) })
        def sm = smd.build()

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['entry']
        // The applier is optional; the entity's own field is left untouched.
        entity.state == 's1'
    }

    def 'a self-transition notifies both hooks for the same state'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', recorder(log, 'exit'))
                .onEntry('enter', recorder(log, 'entry'))
                .transitionsTo('s1', 't', {}) }) })

        when:
        def result = sm.entity(entity).transitionTo('s1')

        then:
        result.success
        log == ['exit', 'entry']
    }

    def 'the firing context reaches listeners untyped, exactly as the host supplied it'() {
        given:
        def seen = []
        def context = new TestContext('audit')
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateExit('capture-exit', { e, ctx, ch -> seen << ctx } as StateListener)
            .onAnyStateEntry('capture-entry', { e, ctx, ch -> seen << ctx } as StateListener) })

        when:
        sm.entity(entity).transitionTo('s2', context)

        then:
        seen.size() == 2
        seen.every { it.is(context) }
    }

    def 'listeners receive a null context when the host supplied none'() {
        given:
        def seen = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateEntry('capture', { e, ctx, ch -> seen << ctx } as StateListener) })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        seen == [null]
    }

    def 'firing through a manual trigger notifies the hooks'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d -> d
            .state('s1', { st -> st
                .onExit('leave', recorder(log, 'exit'))
                .transitionsTo('s2', 't', { t -> t.addManualTrigger('go') }) })
            .state('s2', { st -> st.onEntry('enter', recorder(log, 'entry')) }) })

        when:
        def result = sm.entity(entity).fire('go')

        then:
        result.success
        log == ['exit', 'entry']
    }

    def 'a global listener id colliding with a per-state one is rejected'() {
        when:
        build({ d -> d
            .state('s1', { st -> st
                .onEntry('dup', recorder([], 'x'))
                .transitionsTo('s2', 't', {}) })
            .state('s2', {})
            .onAnyStateEntry('dup', recorder([], 'y')) })

        then:
        def e = thrown(org.transflux.core.exception.TransfluxValidationException)
        e.message.contains("'dup'")
        e.message.contains('already registered')
    }

    def 'a listener class without a no-arg constructor is rejected at build'() {
        when:
        build({ d -> d
            .state('s1', { st -> st.transitionsTo('s2', 't', {}) })
            .state('s2', { st -> st.onEntry('bad', CtorlessListener) }) })

        then:
        def e = thrown(org.transflux.core.exception.TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
    }

    static class CtorlessListener implements StateListener<Entity> {
        CtorlessListener(String arg) {
        }

        @Override
        void onState(Entity entity, Object context, StateChange<Entity> change) {
        }
    }

    private static StateListener<Entity> recorder(List log, String label) {
        return { e, ctx, ch -> log << label } as StateListener
    }

    private static StateListener<Entity> exploding(boolean explode) {
        return { e, ctx, ch ->
            if (explode) {
                throw new IllegalStateException('listener blew up')
            }
        } as StateListener
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

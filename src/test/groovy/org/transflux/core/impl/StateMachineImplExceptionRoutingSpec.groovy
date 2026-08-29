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
import org.transflux.core.TestContext
import org.transflux.core.action.Action
import org.transflux.core.action.Compensation
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * End-to-end routing: which compensation a failing transition picks for each action on its
 * rollback stack. The def surface itself is covered by {@code CompensationRouteDefImplSpec} and
 * {@code CompensationSinkSpec}.
 */
class StateMachineImplExceptionRoutingSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class TimeoutException extends RuntimeException {
        TimeoutException(String message) {
            super(message)
        }
    }

    static class DeclinedException extends RuntimeException {
        final boolean permanent

        DeclinedException(String message, boolean permanent) {
            super(message)
            this.permanent = permanent
        }
    }

    /** Records that it ran, then fails with whatever the fixture handed it. */
    static class ThrowingStep implements Action<Entity, TestContext> {
        final Throwable error

        ThrowingStep(Throwable error) {
            this.error = error
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << 'ran'
            throw error
        }
    }

    /** Succeeds, so its compensation runs only because a later action failed. */
    static class QuietStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << 'quiet'
        }
    }

    /** Supplies a dynamic compensation, to pin where the chain falls through to it and where it does not. */
    static class DynamicCompStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << 'dynamic'
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            return { e, c -> e.trail << '-dynamic' } as Compensation<Entity, TestContext>
        }
    }

    static class ChildCtx {
        String tag
    }

    static class ChildCtxMapper implements ContextMapper<TestContext, ChildCtx> {
        @Override
        ChildCtx mapTo(TestContext parent) {
            return new ChildCtx(tag: parent.tag + '-mapped')
        }
    }

    /** Fails behind a call-site mapper, so the route's compensation should see the child context. */
    static class ChildCtxThrowingStep implements Action<Entity, ChildCtx> {
        @Override
        void execute(Entity entity, ChildCtx context, ExecutingTransition<Entity, ChildCtx> transition) {
            throw new TimeoutException('child-blew-up')
        }
    }

    static class ChildCtxCompensation implements Compensation<Entity, ChildCtx> {
        @Override
        void compensate(Entity entity, ChildCtx context) {
            entity.trail << ('-child:' + (context == null ? 'null-ctx' : context.tag))
        }
    }

    def 'a route matching the failure supplies the compensation'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .forException(TimeoutException).withCompensation(mark('-timeout'))
            })
        })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.compensatedPath*.toString() == ['s1']
        entity.trail == ['ran', '-timeout']
    }

    def 'a route matches subclasses of its declared type'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .forException(RuntimeException).withCompensation(mark('-runtime'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['ran', '-runtime']
    }

    def 'the first matching route wins, in declaration order'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .forException(TimeoutException).withCompensation(mark('-first'))
                 .forException(TimeoutException).withCompensation(mark('-second'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['ran', '-first']
    }

    def 'a guard that rejects falls through to the next route'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new DeclinedException('boom', false)))
                 .forException(DeclinedException)
                     .matching({ DeclinedException e -> e.permanent } as Predicate)
                     .withCompensation(mark('-blacklist'))
                 .forException(DeclinedException).withCompensation(mark('-retry'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['ran', '-retry']
    }

    def 'a guard that accepts selects its route'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new DeclinedException('boom', true)))
                 .forException(DeclinedException)
                     .matching({ DeclinedException e -> e.permanent } as Predicate)
                     .withCompensation(mark('-blacklist'))
                 .forException(DeclinedException).withCompensation(mark('-retry'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['ran', '-blacklist']
    }

    def 'the guard is handed the failure already narrowed to the route type'() {
        given:
        def seen = []
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new DeclinedException('boom', true)))
                 .forException(DeclinedException)
                     .matching({ DeclinedException e -> seen << e.permanent; true } as Predicate)
                     .withCompensation(mark('-blacklist'))
            })
        })

        when:
        sm.executeTransition(new Entity('s1'), 's2')

        then: 'reading a DeclinedException-only member proves the narrowing'
        seen == [true]
    }

    def 'a guard that throws is a non-match rather than a second failure'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .forException(TimeoutException)
                     .matching({ throw new IllegalStateException('guard') } as Predicate)
                     .withCompensation(mark('-guarded'))
                 .forException(RuntimeException).withCompensation(mark('-next'))
            })
        })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the transition still reports the failure that actually ended it'
        result.error instanceof TimeoutException
        entity.trail == ['ran', '-next']
    }

    def 'withCompensation is the fallback when no route matches'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new IllegalStateException('boom')))
                 .withCompensation(mark('-fallback'))
                 .forException(TimeoutException).withCompensation(mark('-timeout'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['ran', '-fallback']
    }

    def 'a matching route replaces the fallback rather than running alongside it'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .withCompensation(mark('-fallback'))
                 .forException(TimeoutException).withCompensation(mark('-timeout'))
            })
        })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'one action, one compensation - the path carries s1 once'
        result.compensatedPath*.toString() == ['s1']
        entity.trail == ['ran', '-timeout']
    }

    def "the fallback's position on the chain does not matter"() {
        given: 'the fallback declared before the routes rather than after'
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new ThrowingStep(new TimeoutException('boom')))
                 .forException(TimeoutException).withCompensation(mark('-timeout'))
                 .withCompensation(mark('-fallback'))
            })
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then: 'the route still wins - withCompensation sets a property, it does not queue a hook'
        entity.trail == ['ran', '-timeout']
    }

    def 'a missed route contributes nothing to the compensated path'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new QuietStep())
                 .forException(TimeoutException).withCompensation(mark('-timeout'))
            })
             .step('s2', new ThrowingStep(new IllegalStateException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 's1 ran, so it is on the executed path, but nothing answered for this failure'
        result.executedPath*.toString() == ['op', 'op/s1', 'op/s2']
        result.compensatedPath.isEmpty()
        entity.trail == ['quiet', 'ran']
    }

    def 'a declared route suppresses the dynamic getCompensation hook'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new DynamicCompStep())
                 .forException(TimeoutException).withCompensation(mark('-routed'))
            })
             .step('s2', new ThrowingStep(new TimeoutException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['dynamic', 'ran', '-routed']
    }

    def 'a route that misses falls through to the dynamic hook'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new DynamicCompStep())
                 .forException(TimeoutException).withCompensation(mark('-routed'))
            })
             .step('s2', new ThrowingStep(new IllegalStateException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'a route that did not match has said nothing, so it does not veto the class rollback'
        result.compensatedPath*.toString() == ['op/s1']
        entity.trail == ['dynamic', 'ran', '-dynamic']
    }

    def 'a declared fallback still suppresses the dynamic hook'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new DynamicCompStep())
                 .withCompensation(mark('-fallback'))
                 .forException(TimeoutException).withCompensation(mark('-routed'))
            })
             .step('s2', new ThrowingStep(new IllegalStateException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then: 'the fallback answers every failure, so the hook is never needed'
        entity.trail == ['dynamic', 'ran', '-fallback']
    }

    def 'an action with routes and no fallback whose class offers nothing is not rolled back'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new QuietStep())
                 .forException(TimeoutException).withCompensation(mark('-routed'))
            })
             .step('s2', new ThrowingStep(new IllegalStateException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'nothing in the chain answers for this failure'
        result.compensatedPath.isEmpty()
        entity.trail == ['quiet', 'ran']
    }

    def 'every stack entry routes against the transition failure, not one it threw itself'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new QuietStep())
                 .forException(TimeoutException).withCompensation(mark('-s1-timeout'))
            })
             .step('s2', new ThrowingStep(new TimeoutException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then: 's1 threw nothing, yet routes against s2 failure'
        entity.trail == ['quiet', 'ran', '-s1-timeout']
    }

    def 'a post-condition violation routes as the validation exception it throws'() {
        given:
        def sm = build({ t ->
            t.step('s1', { StepDef<Entity, TestContext> s ->
                s.using(new QuietStep())
                 .forException(TransfluxValidationException).withCompensation(mark('-post'))
            })
             .postCondition('never', { e, c, tr -> false } as Condition)
        })
        def entity = new Entity('s1')

        when:
        sm.executeTransition(entity, 's2')

        then:
        entity.trail == ['quiet', '-post']
    }

    def "a routed compensation behind a mapper receives the action's own context"() {
        given:
        def sm = buildWithRegistration(
            { smd -> smd.step('child', ChildCtx, { StepDef<Entity, ChildCtx> s ->
                s.using(new ChildCtxThrowingStep())
                 .forException(TimeoutException).withCompensation(ChildCtxCompensation)
            }) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.run('child', new ChildCtxMapper())
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.entity(entity).transitionTo('s2', new TestContext('parent'))

        then:
        result.compensatedPath*.toString() == ['op/child']
        entity.trail == ['-child:parent-mapped']
    }

    def 'a container routes independently of its members'() {
        given:
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.withCompensation(mark('-container'))
             .forException(TimeoutException).withCompensation(mark('-container-timeout'))
             .step('s1', { StepDef<Entity, TestContext> s ->
                 s.using(new QuietStep())
                  .forException(TimeoutException).withCompensation(mark('-member-timeout'))
             })
             .step('s2', new ThrowingStep(new TimeoutException('boom')))
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'container compensation stays additive, and unwinds after its members'
        result.compensatedPath*.toString() == ['op/s1', 'op']
        entity.trail == ['quiet', 'ran', '-member-timeout', '-container-timeout']
    }

    private static Compensation<Entity, TestContext> mark(String tag) {
        return { Entity e, TestContext c -> e.trail << tag } as Compensation<Entity, TestContext>
    }

    private static StateMachine<Entity> build(
            Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        return buildWithRegistration(null, transitionConfigurer)
    }

    private static StateMachine<Entity> buildWithRegistration(
            Consumer<StateMachineDefImpl<Entity>> registrations,
            Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        if (registrations != null) {
            registrations.accept(smd)
        }
        smd.state('s1', { state -> state.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}

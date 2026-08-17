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
import org.transflux.core.TestContext
import org.transflux.core.action.Action
import org.transflux.core.action.OperationDef
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate

/**
 * Execution-path logging, and the level discipline that governs it. The rule held hardest here is
 * that INFO stays off the per-transition path: a host running thousands of transitions a second did
 * not ask for thousands of INFO lines, and the outcome is already on the returned result.
 */
class StateMachineImplExecutionLoggingSpec extends Specification {

    static class Entity {
        String state
        int value

        Entity(String state, int value) {
            this.state = state
            this.value = value
        }
    }

    static class ChildCtx {
        String tag
    }

    static class NoopStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
        }
    }

    static class ThrowingStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            throw new IllegalStateException('boom')
        }
    }

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    // --- transition lifecycle ---

    def 'a successful transition reports start, applier and outcome'() {
        given:
        capture = LogCapture.start('org.transflux.execution.transition')
        def sm = build({ t -> t.step('act', new NoopStep()) })

        when:
        sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        capture.messages().contains('Transition starting, transitionId=t, sourceStateId=s1, targetStateId=s2')
        capture.messages().contains('State applied, transitionId=t, state=s2')
        capture.messages().contains('Transition succeeded, transitionId=t, actions=1')
    }

    def 'a pre-condition rejection is the only trace a rejected transition leaves'() {
        given: 'no listener fires - the start hook sits after the pre-conditions'
        capture = LogCapture.start('org.transflux.execution.transition')
        def sm = build({ t -> t
            .preCondition('never', { Entity e -> false } as Predicate)
            .step('act', new NoopStep()) })

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messages().contains(
            'Transition rejected by pre-condition, transitionId=t, conditionId=never')

        and: 'it never reached the applier or an outcome line'
        capture.messages().every { !it.startsWith('State applied') }
        capture.messages().every { !it.startsWith('Transition succeeded') }
    }

    def 'a failing transition reports the error type and how much was compensated'() {
        given:
        capture = LogCapture.start('org.transflux.execution.transition')
        def sm = build({ t -> t.step('act', new ThrowingStep()) })

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messages().contains(
            'Transition failed, transitionId=t, errorType=java.lang.IllegalStateException, compensated=0')

        and: 'the throwable message is not logged - it is the host\'s and may carry anything'
        capture.messages().every { !it.contains('boom') }
    }

    def 'a missing applier is reported rather than passed over silently'() {
        given: 'the commit point either way, so its absence is worth a line'
        capture = LogCapture.start('org.transflux.execution.transition')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t -> t.step('act', new NoopStep()) }) })
            .state('s2', {})

        when:
        smd.build().entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        capture.messages().contains(
            'No state applier configured, state not written, transitionId=t, state=s2')
    }

    // --- action dispatch ---

    def 'each action reports entry and completion under its qualified path'() {
        given:
        capture = LogCapture.start('org.transflux.execution.action')
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> op ->
            op.step('inner', new NoopStep()) }) })

        when:
        sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then: 'the nested action is qualified beneath its container, matching executedPath'
        capture.messages().contains('Action entered, path=op, context=pass-through')
        capture.messages().contains('Action entered, path=op/inner, context=pass-through')
        capture.messages().contains('Action completed, path=op/inner')
        capture.messages().contains('Action completed, path=op')
    }

    def 'a mapped call site reports the boundary and the child type, never the child value'() {
        given:
        capture = LogCapture.start('org.transflux.execution.action')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .step('child', ChildCtx, { Entity e, ChildCtx c, ExecutingTransition tr -> } as Action)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> op ->
                    op.run('child',
                           { TestContext parent -> new ChildCtx(tag: 'secret-value') } as Function) }) }) })
            .state('s2', {})

        when:
        smd.build().entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        capture.messages().contains("Action entered, path=op/child, context=mapped:${ChildCtx.name}".toString())

        and: 'the mapped context itself never reaches the log'
        capture.messages().every { !it.contains('secret-value') }
    }

    def 'a failing action reports by error type at every enclosing level'() {
        given:
        capture = LogCapture.start('org.transflux.execution.action')
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> op ->
            op.step('inner', new ThrowingStep()) }) })

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messages().contains('Action failed, path=op/inner, errorType=java.lang.IllegalStateException')
        capture.messages().contains('Action failed, path=op, errorType=java.lang.IllegalStateException')
        capture.messages().every { !it.contains('boom') }
    }

    def 'an imperative dispatch reports which scope the id resolved in'() {
        given: 'only a dispatch from inside a body resolves at runtime - container members are bound at build'
        capture = LogCapture.start('org.transflux.execution.action')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .step('sm-level', TestContext, new NoopStep())
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> op -> op
                    .step('inline', new NoopStep())
                    .step('dispatcher', { Entity e, TestContext c, ExecutingTransition tr ->
                        tr.run('inline')
                        tr.run('sm-level')
                    } as Action) }) }) })
            .state('s2', {})

        when:
        smd.build().entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then: 'the container is the active scope, and reaches its own inline id and the SM-level one alike'
        capture.messages().contains('Action id resolved, id=inline, scope=op')
        capture.messages().contains('Action id resolved, id=sm-level, scope=op')
    }

    def 'a dispatch from a transition-attached step resolves against the root scope'() {
        given: 'an imperative action owns no scope, so nothing was pushed'
        capture = LogCapture.start('org.transflux.execution.action')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
            .step('sm-level', TestContext, new NoopStep())
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.step('dispatcher', { Entity e, TestContext c, ExecutingTransition tr ->
                    tr.run('sm-level')
                } as Action) }) })
            .state('s2', {})

        when:
        smd.build().entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        capture.messages().contains('Action id resolved, id=sm-level, scope=root')
    }

    // --- level discipline ---

    def 'no INFO or above is emitted anywhere on a successful transition'() {
        given: 'the rule to hold hardest - the outcome is already on the returned result'
        def sm = build({ t -> t
            .preCondition('ok', { Entity e -> true } as Predicate)
            .postCondition('also-ok', { Entity e -> true } as Predicate)
            .operation('op', { OperationDef<Entity, TestContext> op -> op.step('inner', new NoopStep()) }) })

        and: 'captured after the build, whose own completion INFO is not on the transition path'
        capture = LogCapture.start('org.transflux')

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        result.success
        !capture.events().isEmpty()
        capture.messagesAtOrAbove(Level.INFO).isEmpty()
    }

    def 'a failure with nothing to roll back still stays below INFO'() {
        given: 'the drain INFO is per rollback, not per failure'
        def sm = build({ t -> t.operation('op', { OperationDef<Entity, TestContext> op -> op
            .step('fine', new NoopStep())
            .step('boom', new ThrowingStep()) }) })

        and:
        capture = LogCapture.start('org.transflux')

        when:
        def result = sm.entity(new Entity('s1', 1)).transitionTo('s2', new TestContext())

        then:
        !result.success
        capture.messagesAtOrAbove(Level.INFO).isEmpty()
    }

    private static StateMachine<Entity> build(Consumer<Object> transitionConfig) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        builder.state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
            transitionConfig.accept(t)
        }) })
        builder.state('s2', {})
        return smd.build()
    }
}

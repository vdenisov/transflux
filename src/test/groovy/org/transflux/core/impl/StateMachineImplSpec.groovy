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

import org.transflux.core.Identifiable
import org.transflux.core.StateMachine
import org.transflux.core.TestContext
import org.transflux.core.Transflux
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionResult
import spock.lang.Specification
import spock.lang.Unroll

import static org.transflux.core.TestStateEnum.ACTIVE
import static org.transflux.core.TestStateEnum.EXPIRED
import static org.transflux.core.TestStateEnum.TRIAL

class StateMachineImplSpec extends Specification {

    static class TestEntity {
        String state
        String id

        TestEntity(String id, String state) {
            this.id = id
            this.state = state
        }
    }

    def "state machine should be constructed from definition"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withName("Test SM")
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        expect:
        sm != null
        sm.getDef().getName() == "Test SM"
        sm.getDef().getEntityType() == TestEntity
        sm.getDef().getStates().size() == 2
        sm.getDef().getTransitionsById().size() == 1
        sm.getDef().getStateResolver() != null
    }

    def "resolveCurrentState should return entity's current state"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, {})
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def currentState = sm.resolveCurrentState(entity)

        then:
        currentState == "TRIAL"
    }

    def "resolveCurrentState should throw when entity is null"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, {})
            .build()

        when:
        sm.resolveCurrentState(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Entity cannot be null"
    }

    def "resolveCurrentState should throw when state resolver not configured"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .state(TRIAL, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        sm.resolveCurrentState(entity)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "No state resolver configured for this state machine"
    }

    def "resolveCurrentState should throw when resolver returns unknown state"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> "UNKNOWN" } as StateResolver<TestEntity>)
            .state(TRIAL, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        sm.resolveCurrentState(entity)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("State resolver returned unknown state ID 'UNKNOWN'")
    }

    def "getState should return state by ID"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .state(TRIAL, { s -> s.withName("Trial State") })
            .state(ACTIVE, {})
            .build()

        when:
        def state = sm.getState("TRIAL")

        then:
        state != null
        state.id == "TRIAL"
        state.name == "Trial State"
    }

    def "getState should throw when state does not exist"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .state(TRIAL, {})
            .build()

        when:
        sm.getState("NONEXISTENT")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "State 'NONEXISTENT' does not exist"
    }

    def "getTransition should return transition by ID"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        when:
        def transition = sm.getTransition("trial-to-active")

        then:
        transition != null
        transition.id == "trial-to-active"
        transition.sourceStateId == "TRIAL"
        transition.targetStateId == "ACTIVE"
    }

    def "getTransition should throw when transition does not exist"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .state(TRIAL, {})
            .build()

        when:
        sm.getTransition("nonexistent")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Transition 'nonexistent' does not exist"
    }

    def "executeTransition should invoke state applier with target state on success"() {
        given:
        def applied = []
        def applier = { TestEntity e, String s -> applied << [e.id, s] } as StateApplier<TestEntity>

        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier(applier)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.executeTransition(entity, "ACTIVE")

        then:
        result.success
        applied == [["e1", "ACTIVE"]]
    }

    def "executeTransition should succeed without invoking applier when none configured"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.executeTransition(entity, "ACTIVE")

        then:
        result.success
        result.targetStateId == "ACTIVE"
    }

    def "executeTransition should not invoke applier when source state resolution fails"() {
        given:
        def applied = []
        def applier = { TestEntity e, String s -> applied << [e.id, s] } as StateApplier<TestEntity>

        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> "UNKNOWN" } as StateResolver<TestEntity>)
            .withStateApplier(applier)
            .state(TRIAL, {})
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        sm.executeTransition(entity, "ACTIVE")

        then:
        thrown(TransfluxValidationException)
        applied.isEmpty()
    }

    def "executeTransition should populate startedAt and completedAt timestamps"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.executeTransition(entity, "ACTIVE")

        then:
        result.startedAt != null
        result.completedAt != null
        !result.startedAt.isAfter(result.completedAt)
    }

    def "executeTransition should successfully execute valid transition"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.executeTransition(entity, "ACTIVE")

        then:
        result.success
        result.entity == entity
        result.sourceStateId == "TRIAL"
        result.targetStateId == "ACTIVE"
        result.transitionId == "trial-to-active"
        result.error == null
    }

    def "executeTransition with transition ID should successfully execute"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        def result = sm.executeTransition(entity, "ACTIVE", "trial-to-active")

        then:
        result.success
        result.entity == entity
        result.sourceStateId == "TRIAL"
        result.targetStateId == "ACTIVE"
        result.transitionId == "trial-to-active"
    }

    def "executeTransition should throw when no transition exists"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, {})
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        sm.executeTransition(entity, "ACTIVE")

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "No transition exists from state 'TRIAL' to state 'ACTIVE'"
    }

    def "executeTransition should throw when multiple transitions exist"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s
                .transitionsTo(ACTIVE, "trial-to-active-1", {})
                .transitionsTo(ACTIVE, "trial-to-active-2", {}) })
            .state(ACTIVE, {})
            .build()

        def entity = new TestEntity("e1", "TRIAL")

        when:
        sm.executeTransition(entity, "ACTIVE")

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Multiple transitions exist from state 'TRIAL' to state 'ACTIVE'")
        e.message.contains("trial-to-active-1")
        e.message.contains("trial-to-active-2")
        e.message.contains("Please specify the transition ID explicitly.")
    }

    def "executeTransition with ID should throw when entity not in correct source state"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "trial-to-active", {}) })
            .state(ACTIVE, {})
            .state(EXPIRED, {})
            .build()

        def entity = new TestEntity("e1", "EXPIRED")

        when:
        sm.executeTransition(entity, "ACTIVE", "trial-to-active")

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("Entity is in state 'EXPIRED'")
        e.message.contains("requires source state 'TRIAL'")
    }

    @Unroll
    def "executeTransition should validate parameters: #scenario"() {
        given:
        def sm = Transflux.defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, "t1", {}) })
            .state(ACTIVE, {})
            .build()

        when:
        sm.executeTransition(entity, (String) targetStateId)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expectedMessage

        where:
        scenario              | entity                           | targetStateId | expectedMessage
        'null entity'         | null                             | 'ACTIVE'      | 'Entity cannot be null'
        'null target state'   | new TestEntity("e1", "TRIAL")    | null          | 'Target state ID cannot be null or blank'
        'blank target state'  | new TestEntity("e1", "TRIAL")    | '  '          | 'Target state ID cannot be null or blank'
    }

    def "composite operation invoked directly should run a registered step against the captured scope"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('stamp', new ContextStampStep())
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.compositeOperation('flow', { c -> c.step('stamp') }) }) })
        smd.state(ACTIVE, {})

        def sm = (StateMachineImpl<TestEntity>) smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def ctx = new TestContext()
        def view = new TransitionView<TestEntity, TestContext>(sm, sm.transitions['trial-to-active'], entity, ctx)

        when:
        sm.transitions['trial-to-active'].boundOperation.operation.execute(entity, ctx, view)

        then:
        ctx.tag == 'e1:stamped'
        ctx.counter == 1
        view.executedPath*.toString() == ['stamp']
    }

    def "TransitionResult toString should provide readable output"() {
        given:
        def entity = new TestEntity("e1", "TRIAL")

        when:
        def successResult = TransitionResult.success(entity, "TRIAL", "ACTIVE", "t1")
        def failureResult = TransitionResult.failure(entity, "TRIAL", "ACTIVE", "t1", new RuntimeException("test error"))

        then:
        successResult.toString().contains("success=true")
        successResult.toString().contains("TRIAL -> ACTIVE")
        failureResult.toString().contains("success=false")
        failureResult.toString().contains("test error")
    }

    def "TransitionResult should provide convenient query methods"() {
        given:
        def entity = new TestEntity("e1", "TRIAL")
        def success = TransitionResult.success(entity, "TRIAL", "ACTIVE", "t1")
        def failure = TransitionResult.failure(entity, "TRIAL", "ACTIVE", "t1", new RuntimeException("error"))

        expect:
        success.success
        !success.failure
        success.error == null

        !failure.success
        failure.failure
        failure.error != null
        failure.error.message == "error"
    }

    static class ContextStampStep implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            context.tag = entity.id + ':stamped'
            context.counter++
        }
    }

    static class BumpCounterStep implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            context.counter++
        }
    }

    static class ThrowingStep implements Step<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            throw new IllegalStateException('step blew up')
        }
    }

    static class CallNestedStepOperation implements Operation<TestEntity, TestContext> {
        @Override
        void execute(TestEntity entity, TestContext context, Transition<TestEntity, TestContext> transition) {
            transition.step('stamp')
            transition.step('bump')
        }
    }

    def "transitionTo should run the attached composite operation and populate executedPath in order"() {
        given:
        def appliedState = [:] as Map<TestEntity, String>
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> appliedState[entity] = target } as StateApplier<TestEntity>)
            .step('stamp', new ContextStampStep())
            .step('bump', new BumpCounterStep())
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.compositeOperation('flow', { c ->
            c.step('stamp').step('bump').step('bump')
        }) }) })
        smd.state(ACTIVE, {})

        def sm = smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def context = new TestContext()

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE', context)

        then:
        result.success
        result.executedPath*.toString() == ['flow', 'flow/stamp', 'flow/bump', 'flow/bump']
        result.sourceStateId == 'TRIAL'
        result.targetStateId == 'ACTIVE'
        context.tag == 'e1:stamped'
        context.counter == 3
        appliedState[entity] == 'ACTIVE'
    }

    def "transitionTo with a simple operation should run the operation and apply state"() {
        given:
        def appliedState = [:] as Map<TestEntity, String>
        def operation = { TestEntity entity, TestContext ctx, Transition<TestEntity, TestContext> tx ->
            ctx.tag = 'simple-ran'
        } as Operation<TestEntity, TestContext>

        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> appliedState[entity] = target } as StateApplier<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.simpleOperation('activate', operation) }) })
        smd.state(ACTIVE, {})

        def sm = smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def context = new TestContext()

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE', context)

        then:
        result.success
        result.executedPath*.toString() == ['activate']
        context.tag == 'simple-ran'
        appliedState[entity] == 'ACTIVE'
    }

    def "transitionTo should track step ids invoked from inside a simple operation via transition.step(id)"() {
        given:
        def appliedState = [:] as Map<TestEntity, String>
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> appliedState[entity] = target } as StateApplier<TestEntity>)
            .step('stamp', new ContextStampStep())
            .step('bump', new BumpCounterStep())
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.simpleOperation('orchestrator', new CallNestedStepOperation()) }) })
        smd.state(ACTIVE, {})

        def sm = smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def context = new TestContext()

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE', context)

        then:
        result.success
        result.executedPath*.toString() == ['orchestrator', 'orchestrator/stamp', 'orchestrator/bump']
        context.tag == 'e1:stamped'
        context.counter == 2
        appliedState[entity] == 'ACTIVE'
    }

    def "transitionTo should report failure and skip the state applier when an operation throws"() {
        given:
        def applierInvocations = 0
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> applierInvocations++ } as StateApplier<TestEntity>)
            .step('stamp', new ContextStampStep())
            .step('boom', new ThrowingStep())
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.compositeOperation('flow', { c ->
            c.step('stamp').step('boom')
        }) }) })
        smd.state(ACTIVE, {})

        def sm = smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def context = new TestContext()

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE', context)

        then:
        !result.success
        result.error instanceof IllegalStateException
        result.error.message == 'step blew up'
        result.executedPath*.toString() == ['flow', 'flow/stamp']
        applierInvocations == 0
        context.tag == 'e1:stamped'
    }

    def "transitionTo with a by-id SM-level operation reference records the registered id verbatim"() {
        given:
        def appliedState = [:] as Map<TestEntity, String>
        def captured = []
        def smOp = { TestEntity entity, TestContext ctx, Transition<TestEntity, TestContext> tx ->
            captured << entity.id
            ctx.tag = 'sm-op-ran'
        } as Operation<TestEntity, TestContext>

        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> appliedState[entity] = target } as StateApplier<TestEntity>)
            .operation('sm-level-activate', TestContext, smOp)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', TestContext, { t -> t.operation('sm-level-activate') }) })
        smd.state(ACTIVE, {})

        def sm = smd.build()
        def entity = new TestEntity('e1', 'TRIAL')
        def context = new TestContext()

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE', context)

        then:
        result.success
        // Registered op id appears verbatim — no wrapper composite, no synthesized id.
        result.executedPath*.toString() == ['sm-level-activate']
        captured == ['e1']
        context.tag == 'sm-op-ran'
        appliedState[entity] == 'ACTIVE'
    }

    def "by-id operation reference rejects an unknown id at build time"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.operation('nonexistent') }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'trial-to-active'")
        e.message.contains("'nonexistent'")
        e.message.toLowerCase().contains('unknown operation')
    }

    def "by-id operation reference rejects a kind mismatch (id is a step, not an operation)"() {
        given:
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .step('actually-a-step', new ContextStampStep())
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', { t -> t.operation('actually-a-step') }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'actually-a-step'")
        e.message.toLowerCase().contains('not an operation')
    }

    def "by-id operation reference rejects context-type incompatibility"() {
        given:
        // IdCtx and TestContext are unrelated types; an IdCtx-typed op cannot be attached to a
        // TestContext-typed transition (the SM-level op's required context is not assignable
        // from the transition's context).
        def narrowOp = { TestEntity e, IdCtx b, Transition<TestEntity, IdCtx> tx -> } as Operation<TestEntity, IdCtx>
        def smd = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .operation('narrow-op', IdCtx, narrowOp)
        smd.state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', TestContext, { t -> t.operation('narrow-op') }) })
        smd.state(ACTIVE, {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'trial-to-active'")
        e.message.contains("'narrow-op'")
        e.message.toLowerCase().contains('not assignable')
    }

    def "transitionTo without an attached operation should still apply state and return empty executedPath"() {
        given:
        def appliedState = [:] as Map<TestEntity, String>
        def sm = Transflux.<TestEntity> defineStateMachine()
            .forEntityType(TestEntity)
            .withStateResolver({ e -> e.state } as StateResolver<TestEntity>)
            .withStateApplier({ entity, target -> appliedState[entity] = target } as StateApplier<TestEntity>)
            .state(TRIAL, { s -> s.transitionsTo(ACTIVE, 'trial-to-active', {}) })
            .state(ACTIVE, {})
            .build()
        def entity = new TestEntity('e1', 'TRIAL')

        when:
        def result = sm.entity(entity).transitionTo('ACTIVE')

        then:
        result.success
        result.executedPath == []
        appliedState[entity] == 'ACTIVE'
    }

    @Unroll
    def 'EntityBinding.transitionTo #variant succeeds with Identifiable args'() {
        given:
        def sm = identifiableOverloadSm()
        def entity = new IdEntity('S1')
        def ctx = new IdCtx(input: 'foo')

        when:
        def result = action.call(sm.entity(entity), ctx)

        then:
        result.success
        result.targetStateId == 'S2'

        where:
        variant                                       | action
        '(Identifiable)'                              | { b, c -> b.transitionTo(IdState.S2) }
        '(Identifiable, Identifiable)'                | { b, c -> b.transitionTo(IdState.S2, IdTransition.T1) }
        '(Identifiable, String)'                      | { b, c -> b.transitionTo(IdState.S2, 'T1') }
        '(String, Identifiable)'                      | { b, c -> b.transitionTo('S2', IdTransition.T1) }
        '(Identifiable, Object)'                      | { b, c -> b.transitionTo(IdState.S2, c) }
        '(Identifiable, Identifiable, Object)'        | { b, c -> b.transitionTo(IdState.S2, IdTransition.T1, c) }
        '(Identifiable, String, Object)'              | { b, c -> b.transitionTo(IdState.S2, 'T1', c) }
        '(String, Identifiable, Object)'              | { b, c -> b.transitionTo('S2', IdTransition.T1, c) }
    }

    @Unroll
    def 'StateMachine.executeTransition #variant succeeds with Identifiable args'() {
        given:
        def sm = identifiableOverloadSm()
        def entity = new IdEntity('S1')

        when:
        def result = action.call(sm, entity)

        then:
        result.success
        result.targetStateId == 'S2'

        where:
        variant                                  | action
        '(entity, Identifiable)'                 | { s, e -> s.executeTransition(e, IdState.S2) }
        '(entity, Identifiable, Identifiable)'   | { s, e -> s.executeTransition(e, IdState.S2, IdTransition.T1) }
        '(entity, Identifiable, String)'         | { s, e -> s.executeTransition(e, IdState.S2, 'T1') }
        '(entity, String, Identifiable)'         | { s, e -> s.executeTransition(e, 'S2', IdTransition.T1) }
    }

    @Unroll
    def 'EntityBinding.transitionTo #variant rejects null Identifiable'() {
        given:
        def sm = identifiableOverloadSm()
        def binding = sm.entity(new IdEntity('S1'))

        when:
        action.call(binding)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                | action
        '(null)'                               | { b -> b.transitionTo((Identifiable) null) }
        '(null, identifiable)'                 | { b -> b.transitionTo((Identifiable) null, IdTransition.T1) }
        '(null, string)'                       | { b -> b.transitionTo((Identifiable) null, 'T1') }
        '(string, null)'                       | { b -> b.transitionTo('S2', (Identifiable) null) }
        '(null, object)'                       | { b -> b.transitionTo((Identifiable) null, (Object) null) }
        '(null, identifiable, object)'         | { b -> b.transitionTo((Identifiable) null, IdTransition.T1, (Object) null) }
        '(null, string, object)'               | { b -> b.transitionTo((Identifiable) null, 'T1', (Object) null) }
        '(string, null, object)'               | { b -> b.transitionTo('S2', (Identifiable) null, (Object) null) }
    }

    @Unroll
    def 'StateMachine.executeTransition #variant rejects null Identifiable'() {
        given:
        def sm = identifiableOverloadSm()
        def entity = new IdEntity('S1')

        when:
        action.call(sm, entity)

        then:
        thrown(TransfluxValidationException)

        where:
        variant                                  | action
        '(entity, null)'                         | { s, e -> s.executeTransition(e, (Identifiable) null) }
        '(entity, null, identifiable)'           | { s, e -> s.executeTransition(e, (Identifiable) null, IdTransition.T1) }
        '(entity, null, string)'                 | { s, e -> s.executeTransition(e, (Identifiable) null, 'T1') }
        '(entity, string, null)'                 | { s, e -> s.executeTransition(e, 'S2', (Identifiable) null) }
    }

    private StateMachine<IdEntity> identifiableOverloadSm() {
        return Transflux.<IdEntity> defineStateMachine()
            .forEntityType(IdEntity)
            .withStateResolver({ e -> e.state } as StateResolver<IdEntity>)
            .state('S1', { s -> s.transitionsTo('S2', 'T1', IdCtx, { t -> }) })
            .state('S2', {})
            .build()
    }

    static enum IdState implements Identifiable {
        S1, S2

        @Override
        String getId() { name() }
    }

    static enum IdTransition implements Identifiable {
        T1

        @Override
        String getId() { name() }
    }

    static class IdEntity {
        String state

        IdEntity(String state) { this.state = state }
    }

    static class IdCtx {
        String input
    }
}

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
import org.transflux.core.TestContext
import org.transflux.core.action.Action
import org.transflux.core.action.OperationDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Predicate

/**
 * Build-pipeline logging — the half that answers "why did my definition build into <em>that</em>",
 * and the one place the framework is allowed an INFO line per build.
 */
class StateMachineDefImplBuildLoggingSpec extends Specification {

    static class Entity {
        String state
        int value

        Entity(String state, int value) {
            this.state = state
            this.value = value
        }
    }

    static class NoopStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
        }
    }

    LogCapture capture

    def cleanup() {
        capture?.stop()
    }

    def 'a completed build reports its shape once, at INFO'() {
        given:
        capture = LogCapture.start('org.transflux.build.lifecycle')
        def smd = defWith({ smb -> smb
            .step('sm-step', TestContext, new NoopStep())
            .conditionPredicate('sm-cond', TestContext, { Entity e -> true } as Predicate) })

        when:
        smd.build()

        then:
        capture.messagesAtOrAbove(Level.INFO) == [
            'State machine built, states=2, transitions=1, triggers=1, rootComponents=2'
        ]
    }

    def 'the build INFO is the only INFO the whole tree emits'() {
        given:
        capture = LogCapture.start('org.transflux')

        when:
        defWith({ smb -> smb }).build()

        then:
        capture.messagesAtOrAbove(Level.INFO).size() == 1
        capture.messagesAtOrAbove(Level.INFO).first().startsWith('State machine built')
    }

    def 'each phase names itself before running, so a throw is attributable to the last one reported'() {
        given: 'one logger, so the attribution holds for a host who enabled that leaf alone'
        capture = LogCapture.start('org.transflux.build.lifecycle')

        when:
        defWith({ smb -> smb }).build()

        then: 'in pipeline order'
        def phases = capture.messages().findAll { it.startsWith('Validating') || it.startsWith('Populating') }
        phases == ['Validating context compatibility and cycles',
                   'Populating registries and binding components',
                   'Validating registered components',
                   'Validating conditional branch references']
    }

    def 'a build that fails validation reports the phase it got to, and no completion line'() {
        given:
        capture = LogCapture.start('org.transflux.build')
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> op -> op.run('does-not-exist') }) }) })
            .state('s2', {})

        when:
        smd.build()

        then:
        thrown(TransfluxValidationException)
        capture.messages().contains('Validating context compatibility and cycles')
        capture.messages().every { !it.startsWith('State machine built') }
    }

    def 'each bound component reports what it resolved to, with its context type'() {
        given:
        capture = LogCapture.start('org.transflux.build.binding')
        def smd = defWith({ smb -> smb
            .step('typed-step', TestContext, new NoopStep())
            .step('untyped-step', new NoopStep()) })

        when:
        smd.build()

        then: 'the context type is the half a reader cannot infer - an untyped registration reads as Object'
        capture.messages().contains(
            "Component bound, id=typed-step, kind=step, contextType=${TestContext.name}, scope=root".toString())
        capture.messages().contains(
            'Component bound, id=untyped-step, kind=step, contextType=java.lang.Object, scope=root')
    }

    def 'a declarative container reports under its own authored form'() {
        given:
        capture = LogCapture.start('org.transflux.build.binding')
        def smd = defWith({ smb -> smb.operation('sm-op', TestContext,
            { OperationDef<Entity, TestContext> op -> op.step('inner', new NoopStep()) }) })

        when:
        smd.build()

        then:
        capture.messages().contains(
            "Component bound, id=sm-op, kind=operation, contextType=${TestContext.name}, scope=root".toString())
    }

    def "a container's inline members report the scope that is their only visibility"() {
        given:
        capture = LogCapture.start('org.transflux.build.binding')
        def smd = defWith({ smb -> smb.operation('sm-op', TestContext,
            { OperationDef<Entity, TestContext> op -> op.usingContext(TestContext)
                                                        .step('inline-member', new NoopStep()) }) })

        when:
        smd.build()

        then: 'an inline id resolves from inside its container only, so the line has to name it'
        capture.messages().contains(
            "Component bound, id=inline-member, kind=step, contextType=${TestContext.name}, scope=sm-op".toString())
    }

    def 'registry population and flattening bracket the binding phase'() {
        given:
        capture = LogCapture.start('org.transflux.build.registry')
        def smd = defWith({ smb -> smb.step('sm-step', TestContext, new NoopStep()) })

        when:
        smd.build()

        then:
        capture.messages() == ['Container scopes bound to the root registry',
                               'Registry scopes flattened, rootComponents=1']
    }

    def 'build logging stays below INFO apart from the completion line'() {
        given:
        capture = LogCapture.start('org.transflux.build')

        when:
        defWith({ smb -> smb.step('sm-step', TestContext, new NoopStep()) }).build()

        then:
        capture.messagesAtOrAbove(Level.INFO).size() == 1
    }

    /** One state machine shape reused across the features: two states, one transition, one trigger. */
    private static StateMachineDefImpl<Entity> defWith(Closure smConfig) {
        def smd = new StateMachineDefImpl<Entity>()
        def builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        smConfig.call(builder)
        builder
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.addManualTrigger('go') }) })
            .state('s2', {})
        return smd
    }
}

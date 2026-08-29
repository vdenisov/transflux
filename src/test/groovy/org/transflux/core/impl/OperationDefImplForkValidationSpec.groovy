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

import org.transflux.core.action.Action
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.ForkableContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Consumer

/**
 * How the build treats the context boundary a forked member crosses: nothing is refused, and a
 * member that neither maps nor forks is warned about.
 */
class OperationDefImplForkValidationSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class PlainCtx {}

    static class ForkableCtx implements ForkableContext<ForkableCtx> {
        @Override
        ForkableCtx fork() { return new ForkableCtx() }
    }

    static class NoopAction implements Action<Entity, Object> {
        @Override
        void execute(Entity entity, Object context, ExecutingTransition<Entity, Object> transition) {}
    }

    static class MapToOnly implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return new PlainCtx() }
    }

    static class WritesBack implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return new PlainCtx() }

        @Override
        void mapFrom(Object parent, Object child) {}
    }

    def 'a mapper that writes back is accepted, and simply never writes back'() {
        when: 'nothing is rejected - a branch runs with the mapping already applied'
        build(PlainCtx,
              { smd -> smd.mapper('writes-back', PlainCtx, Object, new WritesBack()) },
              { op -> op.fork('send', 'writes-back') })

        then:
        noExceptionThrown()
    }

    def 'a mapTo-only mapper is accepted'() {
        when:
        build(PlainCtx,
              { smd -> smd.mapper('projects', PlainCtx, Object, new MapToOnly()) },
              { op -> op.fork('send', 'projects') })

        then:
        noExceptionThrown()
    }

    def 'a forked member on a non-forkable context warns, naming the operation, action and type'() {
        when:
        def messages = buildCapturingValidation(PlainCtx, { smd -> }, { op -> op.fork('send') })

        then:
        def warning = messages.find { it.contains('Forked member shares the enclosing context') }
        warning != null
        warning.contains('operationId=op')
        warning.contains('actionId=send')
        warning.contains(PlainCtx.name)
    }

    def 'an undeclared context type warns that forkability could not be checked'() {
        when:
        def messages = buildCapturingValidation(Object, { smd -> }, { op -> op.fork('send') })

        then:
        def warning = messages.find { it.contains('forkability cannot be checked') }
        warning != null
        warning.contains('operationId=op')
        warning.contains('actionId=send')
    }

    def 'a forkable context warns about nothing'() {
        when:
        def messages = buildCapturingValidation(ForkableCtx, { smd -> }, { op -> op.fork('send') })

        then:
        messages.every { !it.contains('Forked member') }
    }

    def 'a Void context warns about nothing: there is no context to share'() {
        when:
        def messages = buildCapturingValidation(Void, { smd -> }, { op -> op.fork('send') })

        then:
        messages.every { !it.contains('Forked member') }
    }

    def 'a mapped forked member warns about nothing: the mapper produces its context'() {
        when:
        def messages = buildCapturingValidation(
            PlainCtx,
            { smd -> smd.mapper('projects', PlainCtx, Object, new MapToOnly()) },
            { op -> op.fork('send', 'projects') })

        then:
        messages.every { !it.contains('Forked member') }
    }

    def 'the warning is per member, not per operation'() {
        when:
        def messages = buildCapturingValidation(
            PlainCtx,
            { smd -> smd.step('other', new NoopAction()).mapper('projects', PlainCtx, Object, new MapToOnly()) },
            { op -> op.fork('send', 'projects').fork('other') })

        then: 'only the unmapped one is reported'
        messages.count { it.contains('Forked member shares') } == 1
        messages.find { it.contains('Forked member shares') }.contains('actionId=other')
    }

    def 'a synchronous member never warns'() {
        when:
        def messages = buildCapturingValidation(PlainCtx, { smd -> }, { op -> op.run('send') })

        then:
        messages.every { !it.contains('Forked member') }
    }

    private static List<String> buildCapturingValidation(Class ctxType, Closure registrations,
                                                         Closure members) {
        def capture = LogCapture.start('org.transflux.build.validation')
        try {
            build(ctxType, registrations, members)
            return capture.messages()
        } finally {
            capture.stop()
        }
    }

    private static void build(Class ctxType, Closure registrations, Closure members) {
        def smd = new StateMachineDefImpl<Entity>()
        def builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        builder.step('send', new NoopAction())
        registrations.call(builder)
        builder.state('s1', { s ->
            s.transitionsTo('s2', 't', ctxType, { t ->
                t.operation('op', { op -> members.call(op) } as Consumer)
            } as Consumer)
        } as Consumer)
        builder.state('s2', {} as Consumer)
        smd.build()
    }
}

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
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate


/**
 * The asynchronous half of a container's member grammar: {@code fork} mirroring the reference
 * shapes {@code run} offers, and {@code forkStep} / {@code forkOperation} / {@code forkConditional}
 * mirroring the declaring ones, each recorded at the same position its synchronous twin is.
 */
class OperationDefImplForkSpec extends Specification {

    static class PassThroughMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return parent }
    }

    static class NoopAction implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> view) {
        }
    }

    def 'every fork overload records a member, in declaration order'() {
        given:
        def def_ = openOperation()

        when:
        def_.fork('a')
            .fork('b', 'm')
            .fork('c', { ctx -> ctx } as ContextMapper)
            .fork('d', new PassThroughMapper())

        then:
        def_.getActionRefs()*.id() == ['a', 'b', 'c', 'd']
    }

    def 'forked and inline members share one ordered list'() {
        given:
        def def_ = openOperation()

        when:
        def_.run('first')
            .fork('second')
            .run('third')

        then:
        def_.getActionRefs()*.id() == ['first', 'second', 'third']
    }

    def 'the mapper-bearing forms carry their call-site mapper'() {
        given:
        def def_ = openOperation()

        when:
        def_.fork('a')
            .fork('b', 'm')
            .fork('c', { ctx -> ctx } as ContextMapper)
            .fork('d', new PassThroughMapper())

        then:
        def refs = def_.getActionRefs()
        refs[0].mapperRef() instanceof MapperRef.PassThrough
        refs[1].mapperRef() instanceof MapperRef.ById
        refs[2].mapperRef() instanceof MapperRef.InlineMapper
        refs[3].mapperRef() instanceof MapperRef.InlineMapper
    }

    @Unroll
    def 'fork(#form) held past its configurer is rejected, naming the operation'() {
        given: 'a def whose configurer has returned'
        def def_ = new OperationDefImpl<Object, Object>('op1')

        when:
        call.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("operation 'op1'")
        e.message.contains('fork')

        where:
        form                || call
        'id'                || { it.fork('a') }
        'id, mapperId'      || { it.fork('a', 'm') }
        'id, lambda mapper' || { it.fork('a', { ctx -> ctx } as ContextMapper) }
        'id, mapper'        || { it.fork('a', new PassThroughMapper()) }
    }

    @Unroll
    def 'fork rejects #bad'() {
        given:
        def def_ = openOperation()

        when:
        call.call(def_)

        then:
        thrown(TransfluxValidationException)

        where:
        bad                    || call
        'a null id'            || { it.fork((String) null) }
        'a blank id'           || { it.fork('  ') }
        'a blank mapper id'    || { it.fork('a', '  ') }
        'a null inline mapper' || { it.fork('a', (ContextMapper) null) }
    }


    @Unroll
    def 'forked declaration #form records a forked member'() {
        given:
        def def_ = openOperation()

        when:
        call.call(def_)

        then: 'one member, and the flag the verb asked for'
        def_.getMembers()*.ref()*.id() == ['a']
        def_.getMembers()*.forked() == [true]

        where:
        form                              || call
        'forkStep(id, action)'            || { it.forkStep('a', new NoopAction()) }
        'forkStep(id, cfg)'               || { it.forkStep('a', { st -> st.using(new NoopAction()) } as Consumer) }
        'forkStep(id, ctx, action)'       || { it.forkStep('a', String, new NoopAction()) }
        'forkStep(id, ctx, map, action)'  || { it.forkStep('a', String, new PassThroughMapper(), new NoopAction()) }
        'forkStep(id, ctx, cfg)'          || { it.forkStep('a', String, { st -> st.using(new NoopAction()) } as Consumer) }
        'forkStep(id, ctx, map, cfg)'     || { it.forkStep('a', String, new PassThroughMapper(), { st -> st.using(new NoopAction()) } as Consumer) }
        'forkOperation(id, cfg)'          || { it.forkOperation('a', { op -> op } as Consumer) }
        'forkOperation(id, ctx, cfg)'     || { it.forkOperation('a', String, { op -> op } as Consumer) }
        'forkOperation(id, ctx, map, cfg)'|| { it.forkOperation('a', String, new PassThroughMapper(), { op -> op } as Consumer) }
        'forkConditional(id, cfg)'        || { it.forkConditional('a', branches) }
        'forkConditional(id, ctx, cfg)'   || { it.forkConditional('a', String, branches) }
        'forkConditional(id, ctx, map, cfg)' || { it.forkConditional('a', String, new PassThroughMapper(), branches) }
    }

    def 'a forked declaration sits in one ordered list beside its synchronous siblings'() {
        given:
        def def_ = openOperation()

        when:
        def_.step('first', new NoopAction())
            .forkStep('second', new NoopAction())
            .run('third')

        then:
        def_.getMembers()*.ref()*.id() == ['first', 'second', 'third']
        def_.getMembers()*.forked() == [false, true, false]
    }

    @Unroll
    def '#verb held past its configurer is rejected, naming the verb and the operation'() {
        given: 'a def whose configurer has returned'
        def def_ = new OperationDefImpl<Object, Object>('op1')

        when:
        call.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("operation 'op1'")
        e.message.contains(verb)

        where:
        verb              || call
        'forkStep'        || { it.forkStep('a', new NoopAction()) }
        'forkOperation'   || { it.forkOperation('a', { op -> op } as Consumer) }
        'forkConditional' || { it.forkConditional('a', branches) }
    }

    /** A conditional configurer with one always-taken branch, so every shape has a body to build. */
    private static Consumer branches = { cs ->
        cs.branch('taken', { b -> b.condition('always', { e -> true } as Predicate) } as Consumer)
    } as Consumer

    private static OperationDefImpl<Object, Object> openOperation() {
        def def_ = new OperationDefImpl<Object, Object>('op1')
        def_.beginConfigurer()
        return def_
    }
}

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

import org.transflux.core.action.ContextMapper
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll


/**
 * The {@code fork} half of a container's reference grammar: the same eight call shapes {@code run}
 * offers, recorded at the same positions.
 */
class OperationDefImplForkSpec extends Specification {

    static class PassThroughMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return parent }
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


    private static OperationDefImpl<Object, Object> openOperation() {
        def def_ = new OperationDefImpl<Object, Object>('op1')
        def_.beginConfigurer()
        return def_
    }
}

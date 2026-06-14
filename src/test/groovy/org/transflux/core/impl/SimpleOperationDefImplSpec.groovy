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


import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.Operation
import org.transflux.core.transition.Transition
import spock.lang.Specification
import spock.lang.Unroll

class SimpleOperationDefImplSpec extends Specification {

    static class NoopOp implements Operation<Object, Object> {
        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
            // no-op
        }
    }

    static class CtorlessOp implements Operation<Object, Object> {
        CtorlessOp(String arg) {
        }

        @Override
        void execute(Object entity, Object context, Transition<Object, Object> transition) {
            // no-op
        }
    }

    def "constructor should reject null id"() {
        when:
        new SimpleOperationDefImpl<Object, Object>(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Operation ID cannot be null or blank'
    }

    @Unroll
    def "constructor should reject blank id: '#id'"() {
        when:
        new SimpleOperationDefImpl<Object, Object>(id)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Operation ID cannot be null or blank'

        where:
        id << ['', '  ']
    }

    def "name and description should be optional and default to null"() {
        when:
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using(new NoopOp())

        then:
        def_.getId() == 'op1'
        def_.getName() == null
        def_.getDescription() == null
    }

    def "name and description should round-trip"() {
        when:
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1')
        def_.beginConfigurer()
        def_.withName('My Op').withDescription('does stuff').using(new NoopOp())

        then:
        def_.getName() == 'My Op'
        def_.getDescription() == 'does stuff'
    }

    def "using(instance) should produce a BoundOperation pointing at the instance"() {
        given:
        def op = new NoopOp()
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1')
        def_.beginConfigurer()
        def_.withName('n').withDescription('d').using(op)

        when:
        def bound = def_.buildBound(null)

        then:
        bound.id() == 'op1'
        bound.name() == 'n'
        bound.description() == 'd'
        bound.operation().is(op)
    }

    def "using(class) should instantiate via no-arg constructor"() {
        given:
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using(NoopOp)

        when:
        def bound = def_.buildBound(null)

        then:
        bound.operation() instanceof NoopOp
    }

    def "using(...) called twice should override and emit a warning (last write wins, instance)"() {
        given:
        def first = new NoopOp()
        def second = new NoopOp()
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using(first).using(second)

        when:
        def bound = def_.buildBound(null)

        then:
        bound.operation().is(second)
    }

    def "using(class) after using(instance) should override the instance"() {
        given:
        def def_ = new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using(new NoopOp()).using(NoopOp)

        when:
        def bound = def_.buildBound(null)

        then:
        bound.operation() instanceof NoopOp
    }

    def "using(instance) should reject null"() {
        when:
        new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using((Operation<Object, Object>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Operation cannot be null'
    }

    def "using(class) should reject null"() {
        when:
        new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using((Class<? extends Operation<Object, Object>>) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Operation class cannot be null'
    }

    def "build without using(...) should fail with a clear message"() {
        when:
        new SimpleOperationDefImpl<Object, Object>('op1').buildBound(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'op1'")
        e.message.contains('using(')
    }

    def "build with a class lacking a no-arg constructor should fail-fast"() {
        when:
        new SimpleOperationDefImpl<Object, Object>('op1').tap { beginConfigurer() }.using(CtorlessOp).buildBound(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('no accessible no-arg constructor')
        e.message.contains('CtorlessOp')
    }
}

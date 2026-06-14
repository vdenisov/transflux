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
//file:noinspection GroovyPointlessBoolean

package org.transflux.core.impl

import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.operation.NoMatchBehavior
import org.transflux.core.operation.Operation
import org.transflux.core.operation.Step
import spock.lang.Specification
import spock.lang.Unroll

class ConfigurableDefImplSpec extends Specification {

    static final Operation<Object, Object> NOOP_OP = { e, c, t -> } as Operation
    static final Step<Object, Object> NOOP_STEP = { e, c, t -> } as Step

    def 'requireConfigurerActive should throw before beginConfigurer'() {
        given:
        def def_ = new IdLessDef("foo")

        when:
        def_.callMutator()

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("foo-scope")
        ex.message.contains("'doStuff'")
    }

    def 'requireConfigurerActive should not throw between begin and end'() {
        given:
        def def_ = new IdLessDef("foo")
        def_.beginConfigurer()

        when:
        def_.callMutator()

        then:
        noExceptionThrown()
    }

    def 'requireConfigurerActive should throw again after endConfigurer'() {
        given:
        def def_ = new IdLessDef("foo")
        def_.beginConfigurer()
        def_.endConfigurer()

        when:
        def_.callMutator()

        then:
        thrown(TransfluxValidationException)
    }

    def 'runConfigurer should set the flag for the duration of the lambda and clear on normal return'() {
        given:
        def def_ = new IdLessDef("foo")
        def insideFlag = null

        when:
        ConfigurableDefImpl.runConfigurer(def_, { d ->
            insideFlag = d.isActive()
        })

        then:
        insideFlag == true
        !def_.isActive()
    }

    def 'runConfigurer should clear the flag even when the lambda throws'() {
        given:
        def def_ = new IdLessDef("foo")

        when:
        ConfigurableDefImpl.runConfigurer(def_, { d -> throw new RuntimeException("boom") })

        then:
        thrown(RuntimeException)
        !def_.isActive()
    }

    def 'id-bearing subclass should embed defKind and id into the guard message'() {
        given:
        def def_ = new TestIdDef("bar")

        when:
        def_.withName("name")

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("test 'bar'")
    }

    def 'id-less subclass should embed its own defLabel into the guard message'() {
        given:
        def def_ = new IdLessDef("baz")

        when:
        def_.callMutator()

        then:
        def ex = thrown(TransfluxValidationException)
        ex.message.contains("baz-scope")
    }

    @Unroll
    def 'post-configurer #mutator on #desc throws naming the def'() {
        given: 'a real def whose configurer has begun and returned'
        def def_ = factory.call()
        def_.beginConfigurer()
        def_.endConfigurer()

        when: 'a mutator is invoked after the configurer returned'
        action.call(def_)

        then: 'the guard rejects it, naming the def and the inert-reference reason'
        def e = thrown(TransfluxValidationException)
        e.message.contains(label)
        e.message.contains("after its configurer has returned")

        where:
        desc                  | mutator              | factory                                                  | action                                    | label
        'composite operation' | 'step'               | { new CompositeOperationDefImpl<Object, Object>('op1') } | { it.step('s') }                          | "operation 'op1'"
        'composite operation' | 'operation'          | { new CompositeOperationDefImpl<Object, Object>('op1') } | { it.operation('o') }                     | "operation 'op1'"
        'composite operation' | 'conditional'        | { new CompositeOperationDefImpl<Object, Object>('op1') } | { it.conditional('cc', {}) }              | "operation 'op1'"
        'composite operation' | 'usingContext'       | { new CompositeOperationDefImpl<Object, Object>('op1') } | { it.usingContext(Object) }               | "operation 'op1'"
        'composite operation' | 'withName'           | { new CompositeOperationDefImpl<Object, Object>('op1') } | { it.withName('n') }                      | "operation 'op1'"
        'simple operation'    | 'using'              | { new SimpleOperationDefImpl<Object, Object>('op1') }    | { it.using(NOOP_OP) }                     | "operation 'op1'"
        'simple operation'    | 'withName'           | { new SimpleOperationDefImpl<Object, Object>('op1') }    | { it.withName('n') }                      | "operation 'op1'"
        'conditional step'    | 'branch'             | { new ConditionalStepDefImpl<Object, Object>('c1') }     | { it.branch('b', {}) }                    | "conditional step 'c1'"
        'conditional step'    | 'defaultBranch'      | { new ConditionalStepDefImpl<Object, Object>('c1') }     | { it.defaultBranch({}) }                  | "conditional step 'c1'"
        'conditional step'    | 'onNoMatch'          | { new ConditionalStepDefImpl<Object, Object>('c1') }     | { it.onNoMatch(NoMatchBehavior.SILENT) }  | "conditional step 'c1'"
        'conditional step'    | 'withName'           | { new ConditionalStepDefImpl<Object, Object>('c1') }     | { it.withName('n') }                      | "conditional step 'c1'"
        'branch'              | 'condition'          | { new BranchDefImpl<Object, Object>('b1') }              | { it.condition('cnd') }                   | "branch 'b1'"
        'branch'              | 'step'               | { new BranchDefImpl<Object, Object>('b1') }              | { it.step('s') }                          | "branch 'b1'"
        'default branch'      | 'step'               | { new DefaultBranchDefImpl<Object, Object>() }           | { it.step('s') }                          | 'default branch'
        'forContext scope'    | 'step'               | { newScope() }                                           | { it.step('s', NOOP_STEP) }               | 'forContext scope for Object'
        'forContext scope'    | 'condition'          | { newScope() }                                           | { it.condition('cnd', 'entity != null') } | 'forContext scope for Object'
        'forContext scope'    | 'compositeOperation' | { newScope() }                                           | { it.compositeOperation('co', {}) }       | 'forContext scope for Object'
        'forContext scope'    | 'operation'          | { newScope() }                                           | { it.operation('o', NOOP_OP) }            | 'forContext scope for Object'
    }

    private static ContextScopeImpl<Object, Object> newScope() {
        return new ContextScopeImpl<Object, Object>(new StateMachineDefImpl<Object>(), Object)
    }

    private static class IdLessDef extends ConfigurableDefImpl {
        private final String label

        IdLessDef(String label) {
            this.label = label
        }

        @Override
        protected String defLabel() {
            return label + "-scope"
        }

        void callMutator() {
            requireConfigurerActive("doStuff")
        }

        boolean isActive() {
            try {
                requireConfigurerActive("probe")
                return true
            } catch (TransfluxValidationException ignored) {
                return false
            }
        }
    }

    private static class TestIdDef extends IdentifiedDefImpl<TestIdDef> {
        TestIdDef(String id) {
            super(id, "test", "Test ID")
        }
    }
}

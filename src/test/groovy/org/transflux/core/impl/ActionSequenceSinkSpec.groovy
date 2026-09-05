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
 * The member grammar's shared storage and guards, driven directly, plus the one thing that cannot
 * be checked from the sink alone: that all four owning defs reach it with the flag their verb
 * asked for. What each owner does with the grammar beyond that is covered by
 * {@code OperationDefImplSpec} and {@code ConditionalOperationDefImplSpec}.
 */
class ActionSequenceSinkSpec extends Specification {

    static class NoopStep implements Action<Object, Object> {
        @Override
        void execute(Object entity, Object context, ExecutingTransition<Object, Object> transition) {
        }
    }

    static class Owner extends ConfigurableDefImpl {
        @Override
        protected String defLabel() {
            return "owner 'o1'"
        }
    }

    def 'members are kept in declaration order, whatever the verb'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when:
        sink.run('a')
        sink.fork('b')
        sink.step('c', new NoopStep(), false)
        sink.conditional('d', { cond -> cond }, false)
        sink.operation('e', { op -> op }, false)
        sink.step('f', new NoopStep(), true)

        then:
        sink.members()*.ref()*.id() == ['a', 'b', 'c', 'd', 'e', 'f']
    }

    def 'only a fork-declared member carries the flag'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when: 'a reference and a declaration of each disposition'
        sink.run('a')
        sink.fork('b')
        sink.step('c', new NoopStep(), false)
        sink.step('d', new NoopStep(), true)
        sink.operation('e', { op -> op }, true)

        then: 'the flag belongs to the call site, whichever verb wrote the member'
        sink.members()*.forked() == [false, true, false, true, true]
    }

    def 'the member view is unmodifiable'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)
        sink.run('a')

        when:
        sink.members().clear()

        then:
        thrown(UnsupportedOperationException)
    }

    def 'a call-site mapper rides on the member reference: #verb'() {
        given:
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when:
        sink."$verb"('a', 'mapper-id')
        sink."$verb"('b', { parent -> parent } as ContextMapper)

        then:
        sink.members()*.ref()*.mapperRef()*.getClass()*.simpleName == ['ById', 'InlineMapper']

        where:
        verb << ['run', 'fork']
    }

    def 'every verb is gated by the configurer guard: #verb'() {
        given: 'a def whose configurer has returned'
        def owner = new Owner()

        when:
        call(new ActionSequenceSink<Object, Object, Object>(owner, owner))

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains(verb)
        e.message.contains("owner 'o1'")

        where:
        verb              | call
        'run'             | { target -> target.run('a') }
        'fork'            | { target -> target.fork('a') }
        'step'            | { target -> target.step('a', new NoopStep(), false) }
        'conditional'     | { target -> target.conditional('a', { cond -> cond }, false) }
        'operation'       | { target -> target.operation('a', { op -> op }, false) }
        'forkStep'        | { target -> target.step('a', new NoopStep(), true) }
        'forkConditional' | { target -> target.conditional('a', { cond -> cond }, true) }
        'forkOperation'   | { target -> target.operation('a', { op -> op }, true) }
    }

    @Unroll
    def 'the instance-form step names the argument it rejected: #bad'() {
        given: 'every other verb labels its own arguments; this one used to let the ref do it'
        def owner = openOwner()
        def sink = new ActionSequenceSink<Object, Object, Object>(owner, owner)

        when:
        call.call(sink)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == expected

        where:
        bad            || call                                          || expected
        'a blank id'   || { it.step('  ', new NoopStep(), false) }       || 'Step ID cannot be null or blank'
        'a null id'    || { it.step(null, new NoopStep(), false) }       || 'Step ID cannot be null or blank'
        'a null action'|| { it.step('a', (Action) null, false) }         || 'Step action cannot be null'
        'forked, blank'|| { it.step('  ', new NoopStep(), true) }        || 'Step ID cannot be null or blank'
    }

    @Unroll
    def '#owner.name: #shape.name records a forked member'() {
        given:
        def def_ = owner.open()

        when:
        shape.call(def_)

        then: 'the flag is the verb\'s, at every position that inherits it'
        owner.members(def_)*.ref()*.id() == ['a']
        owner.members(def_)*.forked() == [true]

        where:
        [owner, shape] << [OWNERS, FORK_SHAPES].combinations()
    }

    @Unroll
    def '#owner.name: #shape.name records a synchronous member'() {
        given: 'the same twelve shapes without the fork prefix, so the pin cuts both ways'
        def def_ = owner.open()

        when:
        shape.call(def_)

        then:
        owner.members(def_)*.forked() == [false]

        where:
        [owner, shape] << [OWNERS, SYNC_SHAPES].combinations()
    }

    /**
     * The four positions that hold an ordered member list. Each hand-delegates all thirty verbs to
     * a sink of its own, so an inherited signature proves nothing about the delegate behind it -
     * which is what makes this cross-product the only thing standing between a copy-paste
     * {@code false} and a member that silently runs in line.
     */
    private static final List<Map> OWNERS = [
        [name    : 'operation',
         open    : { def d = new OperationDefImpl<Object, Object>('op1'); d.beginConfigurer(); d },
         members : { it.getMembers() }],
        [name    : 'branch',
         open    : { def d = new BranchDefImpl<Object, Object>('b1'); d.beginConfigurer(); d },
         members : { it.getMembers() }],
        [name    : 'default branch',
         open    : { def d = new DefaultBranchDefImpl<Object, Object>(); d.beginConfigurer(); d },
         members : { it.getMembers() }],
        [name    : 'transition body',
         open    : { def d = new TransitionDefImpl<Object, Object>('t1', 's1', 's2'); d.beginConfigurer(); d },
         members : { it.getActionDef().getMembers() }],
    ]

    private static final Consumer BRANCHES = { cs ->
        cs.branch('taken', { b -> b.condition('always', { e -> true } as Predicate) } as Consumer)
    } as Consumer

    private static List<Map> shapes(String prefix) {
        String stp = prefix ? prefix + 'Step' : 'step'
        String opn = prefix ? prefix + 'Operation' : 'operation'
        String cnd = prefix ? prefix + 'Conditional' : 'conditional'
        return [
            [name: "${stp}(id, action)", call: { it."$stp"('a', new NoopStep()) }],
            [name: "${stp}(id, cfg)",
             call: { it."$stp"('a', { s -> s.using(new NoopStep()) } as Consumer) }],
            [name: "${stp}(id, ctx, action)", call: { it."$stp"('a', String, new NoopStep()) }],
            [name: "${stp}(id, ctx, mapper, action)",
             call: { it."$stp"('a', String, new PassThroughMapper(), new NoopStep()) }],
            [name: "${stp}(id, ctx, cfg)",
             call: { it."$stp"('a', String, { s -> s.using(new NoopStep()) } as Consumer) }],
            [name: "${stp}(id, ctx, mapper, cfg)",
             call: { it."$stp"('a', String, new PassThroughMapper(),
                               { s -> s.using(new NoopStep()) } as Consumer) }],
            [name: "${opn}(id, cfg)", call: { it."$opn"('a', { op -> op } as Consumer) }],
            [name: "${opn}(id, ctx, cfg)", call: { it."$opn"('a', String, { op -> op } as Consumer) }],
            [name: "${opn}(id, ctx, mapper, cfg)",
             call: { it."$opn"('a', String, new PassThroughMapper(), { op -> op } as Consumer) }],
            [name: "${cnd}(id, cfg)", call: { it."$cnd"('a', BRANCHES) }],
            [name: "${cnd}(id, ctx, cfg)", call: { it."$cnd"('a', String, BRANCHES) }],
            [name: "${cnd}(id, ctx, mapper, cfg)",
             call: { it."$cnd"('a', String, new PassThroughMapper(), BRANCHES) }],
        ]
    }

    private static final List<Map> FORK_SHAPES = shapes('fork')
    private static final List<Map> SYNC_SHAPES = shapes('')

    static class PassThroughMapper implements ContextMapper<Object, Object> {
        @Override
        Object mapTo(Object parent) { return parent }
    }

    private static Owner openOwner() {
        def owner = new Owner()
        owner.beginConfigurer()
        return owner
    }
}

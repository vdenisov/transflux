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
import org.transflux.core.action.AsyncRejectionPolicy
import org.transflux.core.action.ContextMapper
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer

/**
 * The member grammar is written once, on ActionSequenceDelegate: every verb records what it
 * declares on the position's sink, with the fork flag, rejection policy, call-site mapper and
 * declared context its signature asks for, and every position wires itself to that one grammar.
 */
class ActionSequenceDelegateSpec extends Specification {

    static class Ctx {}

    static final Action ACTION = { e, c, t -> } as Action
    static final ContextMapper MAPPER = { p -> new Ctx() } as ContextMapper
    static final Consumer STEP = { s -> s.using(ACTION) } as Consumer
    static final Consumer CHOICE = { c -> } as Consumer
    static final Consumer OPERATION = { o -> } as Consumer

    @Unroll
    def '#verb records the member its signature declares'() {
        given:
        def owner = new OperationDefImpl<Object, Object>('op').tap { beginConfigurer() }

        when:
        def returned = call.call(owner)

        then: 'the verb returns the position, so a chain continues on it'
        returned.is(owner)

        and:
        def member = owner.sequenceSink().members().last()
        member.ref().getClass().simpleName == ref
        member.ref().id() == 'x'
        member.forked() == forked
        member.policy() == policy
        member.ref().mapperRef().getClass().simpleName == mapper
        member.ref().declaredContext() == context

        where:
        verb                                       | call                                                    || ref               | forked | policy                    | mapper         | context
        'run'                                      | { d -> d.run('x') }                                     || 'ById'            | false  | null                      | 'PassThrough'  | null
        'run by mapper id'                         | { d -> d.run('x', 'm') }                                || 'ById'            | false  | null                      | 'ById'         | null
        'run inline mapper'                        | { d -> d.run('x', MAPPER) }                             || 'ById'            | false  | null                      | 'InlineMapper' | null
        'fork'                                     | { d -> d.fork('x') }                                    || 'ById'            | true   | null                      | 'PassThrough'  | null
        'fork by mapper id'                        | { d -> d.fork('x', 'm') }                               || 'ById'            | true   | null                      | 'ById'         | null
        'fork inline mapper'                       | { d -> d.fork('x', MAPPER) }                            || 'ById'            | true   | null                      | 'InlineMapper' | null
        'fork with policy'                         | { d -> d.fork('x', AsyncRejectionPolicy.DROP) }         || 'ById'            | true   | AsyncRejectionPolicy.DROP | 'PassThrough'  | null
        'fork by mapper id with policy'            | { d -> d.fork('x', 'm', AsyncRejectionPolicy.DROP) }    || 'ById'            | true   | AsyncRejectionPolicy.DROP | 'ById'         | null
        'fork inline mapper with policy'           | { d -> d.fork('x', MAPPER, AsyncRejectionPolicy.DROP) } || 'ById'            | true   | AsyncRejectionPolicy.DROP | 'InlineMapper' | null
        'step instance'                            | { d -> d.step('x', ACTION) }                            || 'InlineInstance'  | false  | null                      | 'PassThrough'  | null
        'step configurer'                          | { d -> d.step('x', STEP) }                              || 'InlineDef'       | false  | null                      | 'PassThrough'  | null
        'choice'                                   | { d -> d.choice('x', CHOICE) }                          || 'Choice'          | false  | null                      | 'PassThrough'  | null
        'operation'                                | { d -> d.operation('x', OPERATION) }                    || 'InlineOperation' | false  | null                      | 'PassThrough'  | null
        'forkStep instance'                        | { d -> d.forkStep('x', ACTION) }                        || 'InlineInstance'  | true   | null                      | 'PassThrough'  | null
        'forkStep configurer'                      | { d -> d.forkStep('x', STEP) }                          || 'InlineDef'       | true   | null                      | 'PassThrough'  | null
        'forkChoice'                               | { d -> d.forkChoice('x', CHOICE) }                      || 'Choice'          | true   | null                      | 'PassThrough'  | null
        'forkOperation'                            | { d -> d.forkOperation('x', OPERATION) }                || 'InlineOperation' | true   | null                      | 'PassThrough'  | null
        'step instance typed'                      | { d -> d.step('x', Ctx, ACTION) }                       || 'InlineDef'       | false  | null                      | 'PassThrough'  | Ctx
        'step instance typed, inline mapper'       | { d -> d.step('x', Ctx, MAPPER, ACTION) }               || 'InlineDef'       | false  | null                      | 'InlineMapper' | Ctx
        'step instance typed, mapper id'           | { d -> d.step('x', Ctx, 'm', ACTION) }                  || 'InlineDef'       | false  | null                      | 'ById'         | Ctx
        'step configurer typed'                    | { d -> d.step('x', Ctx, STEP) }                         || 'InlineDef'       | false  | null                      | 'PassThrough'  | Ctx
        'step configurer typed, inline mapper'     | { d -> d.step('x', Ctx, MAPPER, STEP) }                 || 'InlineDef'       | false  | null                      | 'InlineMapper' | Ctx
        'step configurer typed, mapper id'         | { d -> d.step('x', Ctx, 'm', STEP) }                    || 'InlineDef'       | false  | null                      | 'ById'         | Ctx
        'choice typed'                             | { d -> d.choice('x', Ctx, CHOICE) }                     || 'Choice'          | false  | null                      | 'PassThrough'  | Ctx
        'choice typed, inline mapper'              | { d -> d.choice('x', Ctx, MAPPER, CHOICE) }             || 'Choice'          | false  | null                      | 'InlineMapper' | Ctx
        'choice typed, mapper id'                  | { d -> d.choice('x', Ctx, 'm', CHOICE) }                || 'Choice'          | false  | null                      | 'ById'         | Ctx
        'operation typed'                          | { d -> d.operation('x', Ctx, OPERATION) }               || 'InlineOperation' | false  | null                      | 'PassThrough'  | Ctx
        'operation typed, inline mapper'           | { d -> d.operation('x', Ctx, MAPPER, OPERATION) }       || 'InlineOperation' | false  | null                      | 'InlineMapper' | Ctx
        'operation typed, mapper id'               | { d -> d.operation('x', Ctx, 'm', OPERATION) }          || 'InlineOperation' | false  | null                      | 'ById'         | Ctx
        'forkStep instance typed'                  | { d -> d.forkStep('x', Ctx, ACTION) }                   || 'InlineDef'       | true   | null                      | 'PassThrough'  | Ctx
        'forkStep instance typed, inline mapper'   | { d -> d.forkStep('x', Ctx, MAPPER, ACTION) }           || 'InlineDef'       | true   | null                      | 'InlineMapper' | Ctx
        'forkStep instance typed, mapper id'       | { d -> d.forkStep('x', Ctx, 'm', ACTION) }              || 'InlineDef'       | true   | null                      | 'ById'         | Ctx
        'forkStep configurer typed'                | { d -> d.forkStep('x', Ctx, STEP) }                     || 'InlineDef'       | true   | null                      | 'PassThrough'  | Ctx
        'forkStep configurer typed, inline mapper' | { d -> d.forkStep('x', Ctx, MAPPER, STEP) }             || 'InlineDef'       | true   | null                      | 'InlineMapper' | Ctx
        'forkStep configurer typed, mapper id'     | { d -> d.forkStep('x', Ctx, 'm', STEP) }                || 'InlineDef'       | true   | null                      | 'ById'         | Ctx
        'forkChoice typed'                         | { d -> d.forkChoice('x', Ctx, CHOICE) }                 || 'Choice'          | true   | null                      | 'PassThrough'  | Ctx
        'forkChoice typed, inline mapper'          | { d -> d.forkChoice('x', Ctx, MAPPER, CHOICE) }         || 'Choice'          | true   | null                      | 'InlineMapper' | Ctx
        'forkChoice typed, mapper id'              | { d -> d.forkChoice('x', Ctx, 'm', CHOICE) }            || 'Choice'          | true   | null                      | 'ById'         | Ctx
        'forkOperation typed'                      | { d -> d.forkOperation('x', Ctx, OPERATION) }           || 'InlineOperation' | true   | null                      | 'PassThrough'  | Ctx
        'forkOperation typed, inline mapper'       | { d -> d.forkOperation('x', Ctx, MAPPER, OPERATION) }   || 'InlineOperation' | true   | null                      | 'InlineMapper' | Ctx
        'forkOperation typed, mapper id'           | { d -> d.forkOperation('x', Ctx, 'm', OPERATION) }      || 'InlineOperation' | true   | null                      | 'ById'         | Ctx
    }

    @Unroll
    def '#type declares no member verb of its own, so every position runs the one grammar'() {
        expect: 'a class method would beat the interface default and escape the table above'
        type.declaredMethods.findAll { !it.synthetic }*.name.toSet()
            .intersect(['run', 'fork', 'step', 'choice', 'operation',
                        'forkStep', 'forkChoice', 'forkOperation'].toSet()).isEmpty()

        where:
        type << [OperationDefImpl, BranchDefImpl, DefaultBranchDefImpl, TransitionDefImpl]
    }

    @Unroll
    def '#position records its members on its own sink and returns itself'() {
        given:
        def owner = open.call()

        when:
        def returned = owner.run('x')

        then:
        returned.is(owner)
        owner.sequenceSink().members()*.ref()*.id() == ['x']

        where:
        position           | open
        'an operation'     | { new OperationDefImpl<Object, Object>('op').tap { beginConfigurer() } }
        'a branch'         | { new BranchDefImpl<Object, Object>('b').tap { beginConfigurer() } }
        'a default branch' | { new DefaultBranchDefImpl<Object, Object>().tap { beginConfigurer() } }
        'a transition'     | { new TransitionDefImpl<Object, Object>(new StateMachineDefImpl(), 't', 'a', 'b', Object).tap { beginConfigurer() } }
    }
}

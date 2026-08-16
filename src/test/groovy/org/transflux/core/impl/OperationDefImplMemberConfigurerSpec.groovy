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
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.ActionListener
import org.transflux.core.action.BranchDef
import org.transflux.core.action.ConditionalOperationDef
import org.transflux.core.action.DefaultBranchDef
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

/**
 * The configurer form of an inline member declaration, which the bare instance and class forms
 * cannot express: a member declared through it has a def behind it, and so can carry a name, a
 * description, and listeners.
 */
class OperationDefImplMemberConfigurerSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    @Unroll
    def 'a member declared through a configurer at #position runs and carries its listeners'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.transitionsTo('s2', 't', { t -> t.operation('outer', declare.curry(log)) } as Consumer)
            } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        result.executedPath*.toString() == expectedPath
        log == ['start', 'body']

        where:
        position               | declare                                                   || expectedPath
        'a container member'   | OperationDefImplMemberConfigurerSpec.&containerMember      || ['outer', 'outer/member']
        'a branch member'      | OperationDefImplMemberConfigurerSpec.&branchMember         || ['outer', 'outer/pick', 'outer/pick/member']
        'a default-branch member' | OperationDefImplMemberConfigurerSpec.&defaultBranchMember || ['outer', 'outer/pick', 'outer/pick/member']
    }

    def 'the configurer form carries metadata onto the member def'() {
        given:
        def def_ = new OperationDefImpl<Entity, Object>('outer')
        def_.beginConfigurer()

        when:
        def_.step('member', { StepDef s ->
            s.withName('Member').withDescription('declared inline').using({ e, ctx, tr -> } as Action)
        } as Consumer)

        then:
        def ref = def_.getActionRefs().first() as ActionRef.InlineDef
        ref.def().getName() == 'Member'
        ref.def().getDescription() == 'declared inline'
    }

    @Unroll
    def 'the Identifiable sibling at #position delegates through getId()'() {
        given:
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.transitionsTo('s2', 't', { t -> t.operation('outer', declare) } as Consumer)
            } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        result.executedPath.last().leaf() == 'member'

        where:
        position             | declare
        'a container member' | { OperationDef op ->
                                   op.step(idOf('member'), { StepDef s -> s.using({ e, ctx, tr -> } as Action) } as Consumer)
                               } as Consumer
        'a branch member'    | { OperationDef op ->
                                   op.conditional('pick', { ConditionalOperationDef c ->
                                       c.branch('only', { BranchDef b ->
                                           b.condition('always', { e -> true } as Predicate)
                                            .step(idOf('member'), { StepDef s -> s.using({ e, ctx, tr -> } as Action) } as Consumer)
                                       } as Consumer)
                                   } as Consumer)
                               } as Consumer
    }

    @Unroll
    def '#position rejects a blank id'() {
        given:
        def def_ = new OperationDefImpl<Entity, Object>('outer')
        def_.beginConfigurer()

        when:
        declare.call(def_)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step ID cannot be null or blank'

        where:
        position             | declare
        'a container member' | { it.step('  ', { StepDef s -> s.using({ en, ctx, tr -> } as Action) } as Consumer) }
    }

    def 'a null member configurer is rejected'() {
        given:
        def def_ = new OperationDefImpl<Entity, Object>('outer')
        def_.beginConfigurer()

        when:
        def_.step('member', (Consumer) null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message == 'Step configurer cannot be null'
    }

    private static void containerMember(List log, OperationDef<Entity, Object> op) {
        op.step('member', { StepDef s ->
            s.using({ e, ctx, tr -> log << 'body' } as Action)
             .onStart('member-start', { e, ctx, x -> log << 'start' } as ActionListener)
        } as Consumer)
    }

    private static void branchMember(List log, OperationDef<Entity, Object> op) {
        op.conditional('pick', { ConditionalOperationDef c ->
            c.branch('only', { BranchDef b ->
                b.condition('always', { e -> true } as Predicate)
                 .step('member', { StepDef s ->
                     s.using({ e, ctx, tr -> log << 'body' } as Action)
                      .onStart('member-start', { e, ctx, x -> log << 'start' } as ActionListener)
                 } as Consumer)
            } as Consumer)
        } as Consumer)
    }

    private static void defaultBranchMember(List log, OperationDef<Entity, Object> op) {
        op.conditional('pick', { ConditionalOperationDef c ->
            c.branch('never', { BranchDef b ->
                b.condition('no', { e -> false } as Predicate)
                 .step('unreached', { e, ctx, tr -> log << 'unreached' } as Action)
            } as Consumer)
             .defaultBranch({ DefaultBranchDef db ->
                 db.step('member', { StepDef s ->
                     s.using({ e, ctx, tr -> log << 'body' } as Action)
                      .onStart('member-start', { e, ctx, x -> log << 'start' } as ActionListener)
                 } as Consumer)
             } as Consumer)
        } as Consumer)
    }

    private static Identifiable idOf(String value) {
        return { -> value } as Identifiable
    }

    private static StateMachine<Entity> build(Consumer<StateMachineDef<Entity>> cfg) {
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        cfg.accept(builder)
        return smd.build()
    }
}

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

import org.transflux.core.StateMachine
import org.transflux.core.StateMachineDef
import org.transflux.core.action.Action
import org.transflux.core.action.ActionExecution
import org.transflux.core.action.ActionKind
import org.transflux.core.action.ActionListener
import org.transflux.core.action.ActionPhase
import org.transflux.core.action.BranchDef
import org.transflux.core.action.ConditionalOperationDef
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.OperationDef
import org.transflux.core.action.StepDef
import org.transflux.core.condition.Condition
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateListener
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.TransitionListener
import spock.lang.Specification
import spock.lang.Unroll

import java.util.function.Consumer
import java.util.function.Predicate

class StateMachineImplActionListenerSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class ParentContext {
        String tag

        ParentContext(String tag) {
            this.tag = tag
        }
    }

    static class ChildContext {
        String tag

        ChildContext(String tag) {
            this.tag = tag
        }
    }

    static class FailingMapFromMapper implements ContextMapper<ParentContext, ChildContext> {
        @Override
        ChildContext mapTo(ParentContext parent) {
            return new ChildContext(parent.tag + '-mapped')
        }

        @Override
        void mapFrom(ParentContext parent, ChildContext child) {
            throw new IllegalStateException('mapFrom-boom')
        }
    }

    static class CountingListener implements ActionListener<Entity, Object> {
        static int instances = 0

        CountingListener() {
            instances++
        }

        @Override
        void onAction(Entity entity, Object context, ActionExecution<Entity> execution) {
        }
    }

    def 'the start hook runs before the body and the complete hook after it'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.transitionsTo('s2', 't', { t ->
                    t.step('act', { StepDef s ->
                        s.using({ e, ctx, tr -> log << 'body' } as Action)
                         .onStart('before', recorder(log, 'start'))
                         .onComplete('after', recorder(log, 'complete'))
                    } as Consumer)
                } as Consumer)
            } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['start', 'body', 'complete']
    }

    def 'a throwing action notifies start then error, and never complete'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.transitionsTo('s2', 't', { t ->
                    t.step('act', { StepDef s ->
                        s.using({ e, ctx, tr -> throw new IllegalStateException('boom') } as Action)
                         .onStart('before', recorder(log, 'start'))
                         .onComplete('after', recorder(log, 'complete'))
                         .onError('failed', recorder(log, 'error'))
                    } as Consumer)
                } as Consumer)
            } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        result.error.message == 'boom'
        log == ['start', 'error']
    }

    def 'a transition rejected by a pre-condition notifies nothing - no action ever ran'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.onAnyActionStart('any-start', recorder(log, 'start'))
             .onAnyActionError('any-error', recorder(log, 'error'))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.preCondition('never', { e -> false } as Predicate)
                      .step('act', { e, ctx, tr -> } as Action)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        log.isEmpty()
    }

    def 'every action notifies at every depth, with the kind naming the authored form'() {
        given:
        def seen = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.onAnyActionStart('capture', capturing(seen))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.operation('outer', { OperationDef op ->
                         op.step('leaf', { e, ctx, tr -> } as Action)
                           .conditional('choice', { ConditionalOperationDef c ->
                               c.branch('only', { BranchDef b ->
                                   b.condition('always', { e -> true } as Predicate)
                                    .step('branch-leaf', { e, ctx, tr -> } as Action)
                               } as Consumer)
                           } as Consumer)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        seen*.path()*.toString() == ['outer', 'outer/leaf', 'outer/choice', 'outer/choice/branch-leaf']

        and: 'the kind distinguishes the containers from the leaves'
        seen*.kind() == [ActionKind.OPERATION, ActionKind.STEP, ActionKind.OPERATION, ActionKind.STEP]

        and: 'actionId is the leaf of the path'
        seen*.actionId() == ['outer', 'leaf', 'choice', 'branch-leaf']
    }

    def 'the notified paths line up with the executed path'() {
        given:
        def seen = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.step('audit', Object, { StepDef s -> s.using({ e, ctx, tr -> } as Action) } as Consumer)
             .onAnyActionStart('capture', capturing(seen))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.operation('outer', { OperationDef op ->
                         op.step('dispatcher', { e, ctx, tr -> tr.run('audit') } as Action)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        seen*.path()*.toString() == result.executedPath*.toString()
        result.executedPath*.toString() == ['outer', 'outer/dispatcher', 'outer/dispatcher/audit']
    }

    def 'a failure notifies every enclosing action, innermost first'() {
        given:
        def failures = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.onAnyActionError('capture', capturing(failures))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.operation('outer', { OperationDef op ->
                         op.step('boom', { e, ctx, tr -> throw new IllegalStateException('boom') } as Action)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        !result.success
        failures*.path()*.toString() == ['outer/boom', 'outer']

        and: 'every level reports the same throwable, and it is the one the transition reports'
        failures*.error().every { it.is(result.error) }
    }

    def "an action's own listeners run before the global ones, each group in declaration order"() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.onAnyActionStart('any-1', recorder(log, 'any-1'))
             .onAnyActionStart('any-2', recorder(log, 'any-2'))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.step('act', { StepDef s ->
                         s.using({ e, ctx, tr -> } as Action)
                          .onStart('own-1', recorder(log, 'own-1'))
                          .onStart('own-2', recorder(log, 'own-2'))
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['own-1', 'own-2', 'any-1', 'any-2']
    }

    @Unroll
    def 'a listener attached to an action fires when it is reached #callSite'() {
        given:
        def seen = []
        def entity = new Entity(source)
        def sm = callSiteMachine(seen)

        when:
        def result = sm.entity(entity).transitionTo(target)

        then:
        result.success
        seen*.toString() == [expectedPath]

        where:
        callSite                        | source | target || expectedPath
        'as a transition attachment'    | 's1'   | 's2'   || 'charge'
        'as a container member'         | 's2'   | 's3'   || 'wrap/charge'
        'from inside an action body'    | 's3'   | 's4'   || 'caller/charge'
        'as a conditional branch member'| 's4'   | 's5'   || 'router/pick/charge'
    }

    def 'a throwing listener leaves the transition successful and does not suppress the ones after it'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.transitionsTo('s2', 't', { t ->
                    t.step('act', { StepDef s ->
                        s.using({ e, ctx, tr -> } as Action)
                         .onStart('first', { e, ctx, x -> throw new IllegalStateException('listener blew up') } as ActionListener)
                         .onStart('second', recorder(log, 'second'))
                    } as Consumer)
                } as Consumer)
            } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        result.executedPath*.toString() == ['act']
        log == ['second']
        entity.state == 's2'
    }

    def 'the transition handed to a listener refuses to dispatch actions'() {
        given:
        def failures = []
        def stepRuns = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.step('side-effect', Object, { StepDef s ->
                s.using({ e, ctx, tr -> stepRuns << 'ran' } as Action)
            } as Consumer)
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.step('act', { StepDef s ->
                         s.using({ e, ctx, tr -> } as Action)
                          .onStart('meddler', { e, ctx, x ->
                              try {
                                  x.transition().run('side-effect')
                              } catch (Exception ex) {
                                  failures << ex.message
                              }
                          } as ActionListener)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        stepRuns.isEmpty()
        failures.size() == 1
        failures.first().contains('read-only topology view')
    }

    def 'the context a listener sees is the one the action itself runs against'() {
        given:
        def seen = []
        def entity = new Entity('s1')
        def parent = new ParentContext('parent')
        def sm = build({ d ->
            d.step('child', ChildContext, { StepDef s ->
                s.using({ e, ctx, tr -> } as Action)
                 .onStart('capture', { e, ctx, x -> seen << ctx } as ActionListener)
            } as Consumer)
             .mapper('child-from-parent', ParentContext, ChildContext,
                     { ParentContext p -> new ChildContext(p.tag + '-mapped') } as ContextMapper)
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', ParentContext, { t ->
                     t.operation('wrap', { OperationDef op ->
                         op.run('child', 'child-from-parent')
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2', parent)

        then:
        result.success
        seen.size() == 1
        seen.first() instanceof ChildContext
        seen.first().tag == 'parent-mapped'
    }

    def "a failing mapFrom fails the parent - the child's completion stands and it reports no error"() {
        given:
        def log = []
        def failures = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.step('child', ChildContext, { StepDef s ->
                s.using({ e, ctx, tr -> } as Action)
                 .onComplete('child-complete', recorder(log, 'child-complete'))
                 .onError('child-error', recorder(log, 'child-error'))
            } as Consumer)
             .mapper('child-from-parent', ParentContext, ChildContext, new FailingMapFromMapper())
             .onAnyActionError('capture', capturing(failures))
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', ParentContext, { t ->
                     t.operation('wrap', { OperationDef op ->
                         op.run('child', 'child-from-parent')
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentContext('parent'))

        then:
        !result.success
        result.error.message == 'mapFrom-boom'
        log == ['child-complete']

        and: 'the failure is reported at the boundary owner, not at the child'
        failures*.path()*.toString() == ['wrap']
    }

    def 'a step registered inside a forContext block carries its action listeners'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.forContext(ParentContext, { scope ->
                scope.step('charge', { StepDef s ->
                    s.using({ e, ctx, tr -> log << 'body' } as Action)
                     .onStart('before', recorder(log, 'start'))
                     .onComplete('after', recorder(log, 'complete'))
                } as Consumer)
            } as Consumer)
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', ParentContext, { t -> t.run('charge') } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2', new ParentContext('parent'))

        then:
        result.success
        log == ['start', 'body', 'complete']
    }

    def 'an untyped step registered via a configurer carries its action listeners'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.step('charge', { StepDef s ->
                s.using({ e, ctx, tr -> log << 'body' } as Action)
                 .onStart('before', recorder(log, 'start'))
                 .onComplete('after', recorder(log, 'complete'))
            } as Consumer)
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t -> t.run('charge') } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        log == ['start', 'body', 'complete']
    }

    def 'the action hooks interleave with the transition and state hooks in execution-flow order'() {
        given:
        def log = []
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.state('s1', { st ->
                st.onExit('leave', stateRecorder(log, 'state-exit'))
                  .transitionsTo('s2', 't', { t ->
                      t.onStart('t-start', transitionRecorder(log, 'transition-start'))
                       .onComplete('t-complete', transitionRecorder(log, 'transition-complete'))
                       .step('act', { StepDef s ->
                           s.using({ e, ctx, tr -> log << 'body' } as Action)
                            .onStart('a-start', recorder(log, 'action-start'))
                            .onComplete('a-complete', recorder(log, 'action-complete'))
                       } as Consumer)
                  } as Consumer)
            } as Consumer)
             .state('s2', { st -> st.onEntry('enter', stateRecorder(log, 'state-entry')) } as Consumer)
        })

        when:
        sm.entity(entity).transitionTo('s2')

        then:
        log == ['transition-start', 'state-exit', 'action-start', 'body', 'action-complete',
                'transition-complete', 'state-entry']
    }

    def 'a global class-form listener is instantiated once, not once per action'() {
        given:
        CountingListener.instances = 0
        def entity = new Entity('s1')
        def sm = build({ d ->
            d.onAnyActionStart('counter', CountingListener)
             .state('s1', { st ->
                 st.transitionsTo('s2', 't', { t ->
                     t.operation('outer', { OperationDef op ->
                         op.step('one', { e, ctx, tr -> } as Action)
                           .step('two', { e, ctx, tr -> } as Action)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s2', {} as Consumer)
        })

        when:
        def result = sm.entity(entity).transitionTo('s2')

        then:
        result.success
        CountingListener.instances == 1
    }

    def "an action listener id colliding with a state listener id is rejected at build"() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        builder.onAnyStateEntry('dup', { e, ctx, change -> } as StateListener)
               .state('s1', { st ->
                   st.transitionsTo('s2', 't', { t ->
                       t.step('act', { StepDef s ->
                           s.using({ e, ctx, tr -> } as Action).onStart('dup', { e, ctx, x -> } as ActionListener)
                       } as Consumer)
                   } as Consumer)
               } as Consumer)
               .state('s2', {} as Consumer)

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered (declared on step 'act' via onStart)"
    }

    def 'a duplicate id across two actions is rejected at build'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        builder.state('s1', { st ->
            st.transitionsTo('s2', 't', { t ->
                t.operation('outer', { OperationDef op ->
                    op.onStart('dup', { e, ctx, x -> } as ActionListener)
                      .step('leaf', { StepDef s ->
                          s.using({ e, ctx, tr -> } as Action).onError('dup', { e, ctx, x -> } as ActionListener)
                      } as Consumer)
                } as Consumer)
            } as Consumer)
        } as Consumer)
               .state('s2', {} as Consumer)

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "Listener ID 'dup' is already registered (declared on step 'leaf' via onError)"
    }

    def 'building the same definition twice does not report its own action listeners as duplicates'() {
        given:
        def smd = new StateMachineDefImpl<Entity>()
        StateMachineDef<Entity> builder = smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        builder.state('s1', { st ->
            st.transitionsTo('s2', 't', { t ->
                t.step('act', { StepDef s ->
                    s.using({ e, ctx, tr -> } as Action).onStart('audit', { e, ctx, x -> } as ActionListener)
                } as Consumer)
            } as Consumer)
        } as Consumer)
               .state('s2', {} as Consumer)

        when:
        smd.build()
        smd.build()

        then:
        noExceptionThrown()
    }

    private static StateMachine<Entity> callSiteMachine(List seen) {
        return build({ d ->
            d.step('charge', Object, { StepDef s ->
                s.using({ e, ctx, tr -> } as Action)
                 .onStart('charge-audit', { en, ctx, x -> seen << x.path() } as ActionListener)
            } as Consumer)
             .state('s1', { st -> st.transitionsTo('s2', 't1', { t -> t.run('charge') } as Consumer) } as Consumer)
             .state('s2', { st ->
                 st.transitionsTo('s3', 't2', { t ->
                     t.operation('wrap', { OperationDef op -> op.run('charge') } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s3', { st ->
                 st.transitionsTo('s4', 't3', { t ->
                     t.step('caller', { e, ctx, tr -> tr.run('charge') } as Action)
                 } as Consumer)
             } as Consumer)
             .state('s4', { st ->
                 st.transitionsTo('s5', 't4', { t ->
                     t.operation('router', { OperationDef op ->
                         op.conditional('pick', { ConditionalOperationDef c ->
                             c.branch('only', { BranchDef b ->
                                 b.condition('always', { e -> true } as Predicate).run('charge')
                             } as Consumer)
                         } as Consumer)
                     } as Consumer)
                 } as Consumer)
             } as Consumer)
             .state('s5', {} as Consumer)
        })
    }

    private static ActionListener<Entity, Object> recorder(List log, String label) {
        return { e, ctx, x -> log << label } as ActionListener
    }

    private static ActionListener<Entity, Object> capturing(List captured) {
        return { e, ctx, x -> captured << x } as ActionListener
    }

    private static StateListener<Entity> stateRecorder(List log, String label) {
        return { e, ctx, change -> log << label } as StateListener
    }

    private static TransitionListener<Entity, Object> transitionRecorder(List log, String label) {
        return { e, ctx, execution -> log << label } as TransitionListener
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

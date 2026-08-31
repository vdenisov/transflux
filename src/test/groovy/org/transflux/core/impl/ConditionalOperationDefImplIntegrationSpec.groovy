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
import org.transflux.core.TestContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.BranchDef
import org.transflux.core.action.Compensation
import org.transflux.core.action.ContextMapper
import org.transflux.core.action.OperationDef
import org.transflux.core.action.ConditionalOperationDef
import org.transflux.core.action.DefaultBranchDef
import org.transflux.core.action.NoMatchBehavior
import org.transflux.core.action.Action
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

class ConditionalOperationDefImplIntegrationSpec extends Specification {

    static class ChildCtx {
        String tag
    }

    static class Entity {
        String state
        int priority
        String tier
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    /** Records which build's instance was created and which one actually ran. */
    static class Recorder implements Action<Entity, TestContext> {
        static final List<Recorder> CREATED = []
        static final List<Recorder> RAN = []

        Recorder() {
            CREATED << this
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            RAN << this
        }
    }

    static class TrailStep implements Action<Entity, TestContext> {
        final String tag

        TrailStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class TrailWithCompStep implements Action<Entity, TestContext> {
        final String tag

        TrailWithCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            entity.trail << tag
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            String captured = tag
            return { Entity e, TestContext c -> e.trail << ("-" + captured) } as Compensation<Entity, TestContext>
        }
    }

    static class ThrowingStep implements Action<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, ExecutingTransition<Entity, TestContext> transition) {
            throw new RuntimeException('boom')
        }
    }

    def 'three-branch conditional: predicate branch matches'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                        b.condition('critical-cond').step('esc', new TrailStep('escalate'))
                    })
                      .branch('high', { BranchDef<Entity, TestContext> b ->
                        b.conditionExpression('priority >= 8').step('hi', new TrailStep('hi-priority'))
                    })
                      .branch('vip', { BranchDef<Entity, TestContext> b ->
                        b.condition('vip-pred', { Entity e -> e.tier == 'VIP' } as Predicate)
                         .step('vip-step', new TrailStep('vip'))
                    })
                })
            }) })
        def entity = new Entity('s1')
        entity.priority = 12

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['escalate']
        // Branch steps run through the central step runner and are recorded; the conditional
        // executor itself is also dispatched through the runner, so its id appears last.
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/esc']
        applied == ['s2']
    }

    def 'three-branch conditional: expression branch matches'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                        b.condition('critical-cond').step('esc', new TrailStep('escalate'))
                    })
                      .branch('high', { BranchDef<Entity, TestContext> b ->
                        b.conditionExpression('priority >= 8').step('hi', new TrailStep('hi-priority'))
                    })
                      .branch('vip', { BranchDef<Entity, TestContext> b ->
                        b.condition('vip-pred', { Entity e -> e.tier == 'VIP' } as Predicate)
                         .step('vip-step', new TrailStep('vip'))
                    })
                })
            }) })
        def entity = new Entity('s1')
        entity.priority = 9

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['hi-priority']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/hi']
    }

    def 'three-branch conditional: registered-condition-reference branch matches when earlier branches do not'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd
                .condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate)
                .condition('vip-cond', { Entity e -> e.tier == 'VIP' } as Predicate) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                        b.condition('critical-cond').step('esc', new TrailStep('escalate'))
                    })
                      .branch('high', { BranchDef<Entity, TestContext> b ->
                        b.conditionExpression('priority >= 8').step('hi', new TrailStep('hi-priority'))
                    })
                      .branch('vip', { BranchDef<Entity, TestContext> b ->
                        b.condition('vip-cond').step('vip-step', new TrailStep('vip'))
                    })
                })
            }) })
        def entity = new Entity('s1')
        entity.priority = 3
        entity.tier = 'VIP'

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['vip']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/vip-step']
    }

    def 'first-match-wins: only the first matching branch runs'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> true } as Predicate).step('a-step', new TrailStep('A'))
                })
                  .branch('b', { BranchDef<Entity, TestContext> b ->
                    b.condition('b-cond', { Entity e -> true } as Predicate).step('b-step', new TrailStep('B'))
                })
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['A']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/a-step']
    }

    def 'default fallback runs when no branch matches'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> false } as Predicate).step('a-step', new TrailStep('A'))
                })
                  .defaultBranch({ DefaultBranchDef<Entity, TestContext> d ->
                    d.step('default-step', new TrailStep('default'))
                })
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['default']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/default-step']
    }

    def 'WARN with no match and no default: conditional is skipped; preceding steps still recorded'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('before', new TrailStep('before'))
             .conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> false } as Predicate).step('a-step', new TrailStep('A'))
                })
                 .onNoMatch(NoMatchBehavior.WARN)
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['before']
        result.executedPath*.toString() == ['op', 'op/before', 'op/route']
        applied == ['s2']
    }

    def 'SILENT with no match and no default: conditional skipped without logging, transition succeeds'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('before', new TrailStep('before'))
             .conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> false } as Predicate).step('a-step', new TrailStep('A'))
                })
                 .onNoMatch(NoMatchBehavior.SILENT)
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['before']
        // Same shape as the WARN case — the conditional itself was dispatched and returned
        // normally, so its id is on executedPath even though no branch ran.
        result.executedPath*.toString() == ['op', 'op/before', 'op/route']
        applied == ['s2']
    }

    def 'ERROR with no match and no default: transition fails and applier is skipped'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> false } as Predicate).step('a-step', new TrailStep('A'))
                })
                 .onNoMatch(NoMatchBehavior.ERROR)
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error instanceof TransfluxValidationException
        result.error.message == "Conditional operation 'route' had no matching branch and no default"
        applied.isEmpty()
    }

    def 'executed step ids include taken branch steps in order and not other branches'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('taken', { BranchDef<Entity, TestContext> b ->
                    b.condition('taken-cond', { Entity e -> true } as Predicate)
                     .step('t1', new TrailStep('t1'))
                     .step('t2', new TrailStep('t2'))
                     .step('t3', new TrailStep('t3'))
                })
                  .branch('skipped', { BranchDef<Entity, TestContext> b ->
                    b.condition('skipped-cond', { Entity e -> true } as Predicate)
                     .step('s1', new TrailStep('s1'))
                })
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['t1', 't2', 't3']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/t1', 'op/route/t2', 'op/route/t3']
    }

    def 'compensation inside a taken branch runs in LIFO when a subsequent step throws'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('only', { BranchDef<Entity, TestContext> b ->
                    b.condition('only-cond', { Entity e -> true } as Predicate)
                     .step('s1', new TrailWithCompStep('a'))
                     .step('s2', new TrailWithCompStep('b'))
                     .step('s3', new ThrowingStep())
                })
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        !result.success
        result.error.message == 'boom'
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/s1', 'op/route/s2', 'op/route/s3']
        result.compensatedPath*.toString() == ['op/route/s2', 'op/route/s1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
    }

    def 'branch referencing an unknown action id is rejected at build time'() {
        when:
        build([], { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                    b.condition('critical-cond', { Entity e -> true } as Predicate)
                     .run('escalate-immediatly')
                })
            })
        }) })

        then: 'the typo fails the build rather than the first execution that takes this branch'
        def e = thrown(TransfluxValidationException)
        e.message.contains("conditional operation 'route'")
        e.message.contains("branch 'critical'")
        e.message.contains("'escalate-immediatly'")
        e.message.contains('unknown action id')
    }

    def 'default branch referencing an unknown action id is rejected at build time'() {
        when:
        build([], { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('a', { BranchDef<Entity, TestContext> b ->
                    b.condition('a-cond', { Entity e -> false } as Predicate).step('a-step', new TrailStep('A'))
                })
                 .defaultBranch({ DefaultBranchDef<Entity, TestContext> d -> d.run('no-such-step') })
            })
        }) })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("conditional operation 'route'")
        e.message.contains('default branch')
        e.message.contains("'no-such-step'")
        e.message.contains('unknown action id')
    }

    def 'branch reference to a sibling inline step in the same operation builds and runs'() {
        given: 'the referenced id is declared inline by the enclosing operation, not at SM level'
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
            c.step('shared', new TrailStep('shared'))
             .conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('only', { BranchDef<Entity, TestContext> b ->
                    b.condition('only-cond', { Entity e -> true } as Predicate).run('shared')
                })
            })
        }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['shared', 'shared']
        result.executedPath*.toString() == ['op', 'op/shared', 'op/route', 'op/route/shared']
    }

    def 'a branch may reference a container declared after the one holding the conditional'() {
        given: 'members bind after every container is built, not while each one builds'
        def applied = []
        def sm = build(applied,
            { smd -> smd.operation('first', TestContext, { OperationDef<Entity, TestContext> op ->
                    op.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                        cs.branch('only', { BranchDef<Entity, TestContext> b ->
                            b.condition('always', { Entity e -> true } as Predicate).run('second')
                        })
                    })
                })
                .operation('second', TestContext, { OperationDef<Entity, TestContext> op ->
                    op.step('inner', new TrailStep('from-second'))
                })
            },
            { t -> t.run('first') })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['from-second']
        result.executedPath*.toString() == ['first', 'first/route', 'first/route/second', 'first/route/second/inner']
    }

    def 'a failing branch member is named by its whole enclosing position'() {
        when: 'the id is unknown, so resolution reports where it was declared'
        build([], { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                        b.condition('critical-cond', { Entity e -> true } as Predicate)
                         .run('ghost')
                    })
                })
            }) })

        then: 'each level of the nesting appears, so the branch is locatable and not merely named'
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("transition 't' > operation 'op' > conditional operation 'route'"
                                 + " > branch 'critical' references unknown action id 'ghost'")
    }

    def 'branch referencing an id registered as a condition is rejected at build time'() {
        when:
        build([], { smd -> smd.condition('not-an-action', { Entity e -> true } as Predicate) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('critical', { BranchDef<Entity, TestContext> b ->
                        b.condition('critical-cond', { Entity e -> true } as Predicate)
                         .run('not-an-action')
                    })
                })
            }) })

        then: 'the id resolves, but to the wrong kind of component'
        def e = thrown(TransfluxValidationException)
        e.message.contains("conditional operation 'route'")
        e.message.contains("branch 'critical'")
        e.message.contains("'not-an-action'")
        e.message.contains('not an action')
    }

    def 'a mapped branch member runs against the mapped child context'() {
        given: 'the branch member crosses a context boundary the container member grammar allows'
        def applied = []
        def seen = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('child-step', ChildCtx, { Entity e, ChildCtx c, ExecutingTransition tr ->
                seen << c.tag
            } as Action)
            .mapper('child-from-parent', TestContext, ChildCtx,
                    { TestContext p -> new ChildCtx(tag: p.tag + '-mapped') } as ContextMapper)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> c ->
                    c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                        cs.branch('only', { BranchDef<Entity, TestContext> b ->
                            b.condition('always', { Entity e -> true } as Predicate)
                             .run('child-step', 'child-from-parent')
                        })
                    })
                })
            }) })
            .state('s2', {})
        def entity = new Entity('s1')

        when:
        def result = smd.build().entity(entity).transitionTo('s2', new TestContext('parent-tag'))

        then:
        result.success
        seen == ['parent-tag-mapped']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/child-step']
    }

    def 'an inline mapper on a branch member is applied at the boundary'() {
        given:
        def applied = []
        def seen = []
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
            .step('child-step', ChildCtx, { Entity e, ChildCtx c, ExecutingTransition tr ->
                seen << c.tag
            } as Action)
            .state('s1', { st -> st.transitionsTo('s2', 't', TestContext, { t ->
                t.operation('op', { OperationDef<Entity, TestContext> c ->
                    c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                        cs.branch('only', { BranchDef<Entity, TestContext> b ->
                            b.condition('always', { Entity e -> true } as Predicate)
                             .run('child-step',
                                  { TestContext p -> new ChildCtx(tag: p.tag + '-inline') } as ContextMapper)
                        })
                    })
                })
            }) })
            .state('s2', {})

        when:
        def result = smd.build().entity(new Entity('s1')).transitionTo('s2', new TestContext('parent-tag'))

        then:
        result.success
        seen == ['parent-tag-inline']
    }

    def 'a conditional nested inside a branch selects and reports at two levels'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('outer', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('high', { BranchDef<Entity, TestContext> b ->
                        b.conditionExpression('priority >= 5')
                         .conditional('inner', { ConditionalOperationDef<Entity, TestContext> ics ->
                             ics.branch('vip', { BranchDef<Entity, TestContext> ib ->
                                 ib.condition('is-vip', { Entity e -> e.tier == 'VIP' } as Predicate)
                                   .step('vip-leaf', new TrailStep('vip-leaf'))
                             }).defaultBranch({ DefaultBranchDef<Entity, TestContext> d ->
                                 d.step('plain-leaf', new TrailStep('plain-leaf'))
                             })
                         })
                    }).defaultBranch({ DefaultBranchDef<Entity, TestContext> d ->
                        d.step('low-leaf', new TrailStep('low-leaf'))
                    })
                })
            }) })
        def entity = new Entity('s1')
        entity.priority = 7
        entity.tier = 'VIP'

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'each conditional pushed its own id, so the leaf reports at full depth'
        result.success
        entity.trail == ['vip-leaf']
        result.executedPath*.toString() == ['op', 'op/outer', 'op/outer/inner', 'op/outer/inner/vip-leaf']
    }

    def 'a nested conditional falls through to its own default branch'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('outer', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('high', { BranchDef<Entity, TestContext> b ->
                        b.conditionExpression('priority >= 5')
                         .conditional('inner', { ConditionalOperationDef<Entity, TestContext> ics ->
                             ics.branch('vip', { BranchDef<Entity, TestContext> ib ->
                                 ib.condition('is-vip', { Entity e -> e.tier == 'VIP' } as Predicate)
                                   .step('vip-leaf', new TrailStep('vip-leaf'))
                             }).defaultBranch({ DefaultBranchDef<Entity, TestContext> d ->
                                 d.step('plain-leaf', new TrailStep('plain-leaf'))
                             })
                         })
                    })
                })
            }) })
        def entity = new Entity('s1')
        entity.priority = 7
        entity.tier = 'REGULAR'

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['plain-leaf']
    }

    def 'a branch member declared inside a nested conditional resolves in the enclosing container scope'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.step('shared', new TrailStep('shared'))
                 .conditional('outer', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('only', { BranchDef<Entity, TestContext> b ->
                        b.condition('always', { Entity e -> true } as Predicate)
                         .conditional('inner', { ConditionalOperationDef<Entity, TestContext> ics ->
                             ics.branch('deep', { BranchDef<Entity, TestContext> ib ->
                                 ib.condition('also-always', { Entity e -> true } as Predicate)
                                   .run('shared')
                             })
                         })
                    })
                })
            }) })

        when:
        def entity = new Entity('s1')
        def result = sm.executeTransition(entity, 's2')

        then: 'the inline sibling is visible two conditionals deep'
        result.success
        entity.trail == ['shared', 'shared']
        result.executedPath*.toString() == ['op', 'op/shared', 'op/outer', 'op/outer/inner', 'op/outer/inner/shared']
    }

    def 'one branch reaches an action another branch declared'() {
        given: 'the branches share the conditional scope, so a common step is declared once'
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                    .branch('never', { BranchDef<Entity, TestContext> b -> b
                        .condition('no', { Entity e -> false } as Predicate)
                        .step('common', new TrailStep('common')) })
                    .branch('taken', { BranchDef<Entity, TestContext> b -> b
                        .condition('yes', { Entity e -> true } as Predicate)
                        .run('common') }) })
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['common']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/common']
    }

    def "a sibling of the conditional cannot reach what a branch declared"() {
        when: 'the conditional scope is private from outside, as a container scope is'
        build([], { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c -> c
                .conditional('route', { ConditionalOperationDef<Entity, TestContext> cs ->
                    cs.branch('only', { BranchDef<Entity, TestContext> b -> b
                        .condition('yes', { Entity e -> true } as Predicate)
                        .step('buried', new TrailStep('buried')) }) })
                .run('buried') })
            })

        then: 'and the diagnostic says where the id does live'
        def e = thrown(TransfluxValidationException)
        e.message.contains("unknown action id 'buried'")
        e.message.contains("composite 'route'")
    }

    def "an action dispatched from a branch member's body resolves in the conditional's scope"() {
        given: 'the executor pushes its scope, so an imperative run(id) sees what branches share'
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                    .branch('other', { BranchDef<Entity, TestContext> b -> b
                        .condition('no', { Entity e -> false } as Predicate)
                        .step('sibling-leaf', new TrailStep('sibling-leaf')) })
                    .branch('taken', { BranchDef<Entity, TestContext> b -> b
                        .condition('yes', { Entity e -> true } as Predicate)
                        .step('dispatcher', { Entity e, TestContext ctx, ExecutingTransition view ->
                            view.run('sibling-leaf')
                        } as Action) }) })
            }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['sibling-leaf']
        result.executedPath*.toString().contains('op/route/dispatcher/sibling-leaf')
    }

    def 'a machine keeps the conditional scope it was built with when the def is built again'() {
        given: 'a class-based inline member, so each build instantiates its own'
        Recorder.CREATED.clear()
        Recorder.RAN.clear()
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> e.state = s } as StateApplier<Entity>)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', TestContext, { t ->
            t.operation('op', { OperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                    .branch('other', { BranchDef<Entity, TestContext> b -> b
                        .condition('no', { Entity e -> false } as Predicate)
                        .step('leaf', Recorder) })
                    .branch('taken', { BranchDef<Entity, TestContext> b -> b
                        .condition('yes', { Entity e -> true } as Predicate)
                        .step('dispatcher', { Entity e, TestContext ctx, ExecutingTransition view ->
                            view.run('leaf')
                        } as Action) }) })
            })
        }) })
        smd.state('s2', {})

        def first = smd.build()
        smd.build()

        when: 'the first machine runs after a second was built from the same def'
        def result = first.executeTransition(new Entity('s1'), 's2')

        then: 'it dispatched the member from its own build, not from the later one'
        result.success
        Recorder.CREATED.size() == 2
        Recorder.RAN.size() == 1
        Recorder.RAN[0].is(Recorder.CREATED[0])
    }

    def 'a conditional attaches straight to a transition, with no wrapping operation'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                .branch('low', { BranchDef<Entity, TestContext> b -> b
                    .condition('is-low', { Entity e -> false } as Predicate)
                    .step('low-step', new TrailStep('low')) })
                .branch('high', { BranchDef<Entity, TestContext> b -> b
                    .condition('is-high', { Entity e -> true } as Predicate)
                    .step('high-step', new TrailStep('high')) }) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the conditional is the root action, so no synthetic level appears on the path'
        result.success
        entity.trail == ['high']
        result.executedPath*.toString() == ['route', 'route/high-step']
        applied == ['s2']
    }

    def 'a transition-attached conditional falls through to its default branch'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                .branch('never', { BranchDef<Entity, TestContext> b -> b
                    .condition('no', { Entity e -> false } as Predicate)
                    .step('unreached', new TrailStep('unreached')) })
                .defaultBranch({ DefaultBranchDef<Entity, TestContext> d ->
                    d.step('fallback', new TrailStep('fallback')) }) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['fallback']
        result.executedPath*.toString() == ['route', 'route/fallback']
    }

    def 'a transition-attached conditional under ERROR fails the transition when nothing matches'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> },
            { t -> t.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                .onNoMatch(NoMatchBehavior.ERROR)
                .branch('never', { BranchDef<Entity, TestContext> b -> b
                    .condition('no', { Entity e -> false } as Predicate)
                    .step('unreached', new TrailStep('unreached')) }) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the conditional is the root action, so its failure is the transition failure'
        !result.success
        result.error.message.contains("'route'")
        entity.trail.isEmpty()
        applied.isEmpty()
    }

    def "a transition-attached conditional's branches share its scope"() {
        given: 'the scope is parented on the root registry, there being no enclosing container'
        def applied = []
        def sm = build(applied, { smd -> smd.step('sm-level', new TrailStep('sm-level')) },
            { t -> t.conditional('route', { ConditionalOperationDef<Entity, TestContext> cs -> cs
                .branch('never', { BranchDef<Entity, TestContext> b -> b
                    .condition('no', { Entity e -> false } as Predicate)
                    .step('shared', new TrailStep('shared')) })
                .branch('taken', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .run('shared')
                    .run('sm-level') }) }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'a branch reaches its sibling branch, and still walks out to the root'
        result.success
        entity.trail == ['shared', 'sm-level']
        result.executedPath*.toString() == ['route', 'route/shared', 'route/sm-level']
    }

    def 'a conditional registered at SM level is referenced by id like any other action'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.conditional('route', TestContext, { ConditionalOperationDef<Entity, TestContext> cs -> cs
                .branch('never', { BranchDef<Entity, TestContext> b -> b
                    .condition('no', { Entity e -> false } as Predicate)
                    .step('unreached', new TrailStep('unreached')) })
                .branch('taken', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .step('chosen', new TrailStep('chosen')) }) }) },
            { t -> t.operation('op', { OperationDef<Entity, TestContext> c -> c.run('route') }) })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then: 'the call site says nothing about which form it reached'
        result.success
        entity.trail == ['chosen']
        result.executedPath*.toString() == ['op', 'op/route', 'op/route/chosen']
    }

    def 'a registered conditional can be attached to a transition by id'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.conditional('route', TestContext, { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('taken', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .step('chosen', new TrailStep('chosen')) }) }) },
            { t -> t.run('route') })
        def entity = new Entity('s1')

        when:
        def result = sm.executeTransition(entity, 's2')

        then:
        result.success
        entity.trail == ['chosen']
        result.executedPath*.toString() == ['route', 'route/chosen']
    }

    def 'a registered conditional shares the action namespace'() {
        when:
        build([], { smd ->
            smd.step('clash', new TrailStep('step'))
            smd.conditional('clash', TestContext, { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('b', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .step('inner', new TrailStep('inner')) }) })
        }, { t -> t.run('clash') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('clash')
    }

    def 'a cycle through a registered conditional is rejected'() {
        when: 'the conditional is a cycle node like any other registered action'
        build([], { smd ->
            smd.conditional('route', TestContext, { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('b', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .run('route') }) })
        }, { t -> t.run('route') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.endsWith('cycle detected: route -> route')
    }

    def 'a registered conditional is named as one when a branch fails to resolve'() {
        when: 'the same def reported at a transition slot reads the same way here'
        build([], { smd ->
            smd.conditional('route', TestContext, { ConditionalOperationDef<Entity, TestContext> cs ->
                cs.branch('critical', { BranchDef<Entity, TestContext> b -> b
                    .condition('yes', { Entity e -> true } as Predicate)
                    .run('ghost') }) })
        }, { t -> t.run('route') })

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("SM-level conditional operation 'route' > branch 'critical'"
                                 + " references unknown action id 'ghost'")
    }

    private static StateMachine<Entity> build(List<String> applied,
                                              Consumer<StateMachineDefImpl<Entity>> smdRegistrations,
                                              Consumer<TransitionDef<Entity, TestContext>> transitionConfigurer) {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .withStateApplier({ e, s -> applied.add(s); e.state = s } as StateApplier<Entity>)
        smdRegistrations.accept(smd)
        smd.state('s1', { s -> s.transitionsTo('s2', 't', TestContext, transitionConfigurer) })
            .state('s2', {})
        return smd.build()
    }
}

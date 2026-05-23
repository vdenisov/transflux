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

import org.transflux.core.*
import org.transflux.core.state.*
import org.transflux.core.transition.*
import org.transflux.core.operation.*
import org.transflux.core.condition.*
import org.transflux.core.exception.*

import org.transflux.core.impl.*

import org.transflux.core.StateMachine
import org.transflux.core.impl.StateMachineDefImpl
import org.transflux.core.TestContext
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateApplier
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import org.transflux.core.transition.TransitionDef
import spock.lang.Specification

import java.util.function.Consumer
import java.util.function.Predicate

class ConditionalStepDefImplIntegrationSpec extends Specification {

    static class Entity {
        String state
        int priority
        String tier
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class TrailStep implements Step<Entity, TestContext> {
        final String tag

        TrailStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }
    }

    static class TrailWithCompStep implements Step<Entity, TestContext> {
        final String tag

        TrailWithCompStep(String tag) {
            this.tag = tag
        }

        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            entity.trail << tag
        }

        @Override
        Compensation<Entity, TestContext> getCompensation(Entity entity, TestContext context) {
            String captured = tag
            return { Entity e, TestContext c -> e.trail << ("-" + captured) } as Compensation<Entity, TestContext>
        }
    }

    static class ThrowingStep implements Step<Entity, TestContext> {
        @Override
        void execute(Entity entity, TestContext context, Transition<Entity, TestContext> transition) {
            throw new RuntimeException('boom')
        }
    }

    def 'three-branch conditional: predicate branch matches'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate) },
            { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['esc', 'route']
        applied == ['s2']
    }

    def 'three-branch conditional: expression branch matches'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd.condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate) },
            { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['hi', 'route']
    }

    def 'three-branch conditional: registered-condition-reference branch matches when earlier branches do not'() {
        given:
        def applied = []
        def sm = build(applied,
            { smd -> smd
                .condition('critical-cond', { Entity e -> e.priority >= 10 } as Predicate)
                .condition('vip-cond', { Entity e -> e.tier == 'VIP' } as Predicate) },
            { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
                c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['vip-step', 'route']
    }

    def 'first-match-wins: only the first matching branch runs'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['a-step', 'route']
    }

    def 'default fallback runs when no branch matches'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['default-step', 'route']
    }

    def 'WARN with no match and no default: conditional is skipped; preceding steps still recorded'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('before', new TrailStep('before'))
             .conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['before', 'route']
        applied == ['s2']
    }

    def 'SILENT with no match and no default: conditional skipped without logging, transition succeeds'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.step('before', new TrailStep('before'))
             .conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        // normally, so its id is on executedStepIds even though no branch ran.
        result.executedStepIds*.toString() == ['before', 'route']
        applied == ['s2']
    }

    def 'ERROR with no match and no default: transition fails and applier is skipped'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.error.message == "Conditional step 'route' had no matching branch and no default"
        applied.isEmpty()
    }

    def 'executed step ids include taken branch steps in order and not other branches'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['t1', 't2', 't3', 'route']
    }

    def 'compensation inside a taken branch runs in LIFO when a subsequent step throws'() {
        given:
        def applied = []
        def sm = build(applied, { smd -> }, { t -> t.compositeOperation('op', { CompositeOperationDef<Entity, TestContext> c ->
            c.conditional('route', { ConditionalStepDef<Entity, TestContext> cs ->
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
        result.executedStepIds*.toString() == ['s1', 's2']
        result.compensatedStepIds*.toString() == ['s2', 's1']
        entity.trail == ['a', 'b', '-b', '-a']
        applied.isEmpty()
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

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

import org.transflux.core.ContextScope
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.action.BranchDef
import org.transflux.core.action.ConditionalOperationDef
import org.transflux.core.action.DefaultBranchDef
import org.transflux.core.action.OperationDef
import org.transflux.core.action.Action
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.ExecutingTransition
import spock.lang.Specification

import java.util.function.Predicate

class StateMachineDefImplNestedCycleDetectionSpec extends Specification {

    static class Entity {
        String state

        Entity(String state) {
            this.state = state
        }
    }

    static class Ctx { }

    static class NoopStep implements Action<Entity, Ctx> {
        @Override
        void execute(Entity entity, Ctx context, ExecutingTransition<Entity, Ctx> transition) { }
    }

    def 'composite referring to itself by id is rejected with a clear cycle message'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder', new NoopStep()).run('a')   // self-reference
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('a')
    }

    def 'two composites referring to each other (A -> B -> A) are rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder-a', new NoopStep()).run('b')
            }).operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('placeholder-b', new NoopStep()).run('a')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'three composites forming A -> B -> C -> A are rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).run('b')
            }).operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep()).run('c')
            }).operation('c', { OperationDef<Entity, Ctx> c ->
                c.step('pc', new NoopStep()).run('a')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'acyclic composite chain A -> B is accepted'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep())
            }).operation('a', { OperationDef<Entity, Ctx> c ->
                c.step('pa', new NoopStep()).run('b')
            })
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'a composite whose conditional branch references it back is rejected'() {
        given: 'a branch member dispatches through the same path a container member does'
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, Ctx> cs ->
                    cs.branch('only', { BranchDef<Entity, Ctx> b ->
                        b.condition('always', { Entity e -> true } as Predicate).run('a')
                    })
                })
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('a')
    }

    def 'a cycle closed through two composites via a branch is rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, Ctx> cs ->
                    cs.branch('only', { BranchDef<Entity, Ctx> b ->
                        b.condition('always', { Entity e -> true } as Predicate).run('b')
                    })
                })
            }).operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep()).run('a')
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'a cycle closed through a default branch is rejected too'() {
        given: 'the default branch is a member list like any other, and is easy to miss in the walk'
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, Ctx> cs ->
                    cs.branch('never', { BranchDef<Entity, Ctx> b ->
                        b.condition('nope', { Entity e -> false } as Predicate)
                         .step('unreached', new NoopStep())
                    }).defaultBranch({ DefaultBranchDef<Entity, Ctx> d -> d.run('a') })
                })
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'a branch referencing another composite acyclically still builds'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('b', { OperationDef<Entity, Ctx> c ->
                c.step('pb', new NoopStep())
            }).operation('a', { OperationDef<Entity, Ctx> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, Ctx> cs ->
                    cs.branch('only', { BranchDef<Entity, Ctx> b ->
                        b.condition('always', { Entity e -> true } as Predicate).run('b')
                    })
                })
            })
        })

        when:
        def sm = smd.build()

        then:
        sm != null
    }

    def 'an inline container referencing itself by id is rejected'() {
        given: 'it is registered in the enclosing scope, so its own run(id) resolves and recurses'
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.operation('inner', { OperationDef<Entity, Ctx> nested -> nested.run('inner') })
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('inner')
    }

    def 'a conditional whose own branch references it by id is rejected'() {
        given: 'a conditional is registered under its id too, so a branch can name it'
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.conditional('route', { ConditionalOperationDef<Entity, Ctx> cs ->
                    cs.branch('base', { BranchDef<Entity, Ctx> b ->
                        b.condition('always', { Entity e -> true } as Predicate).run('route')
                    })
                })
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('route')
    }

    def 'a cycle closed from inside an inline container back to its enclosing one is rejected'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.operation('inner', { OperationDef<Entity, Ctx> nested -> nested.run('a') })
            })
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
    }

    def 'an inline container referencing an SM-level container acyclically still builds'() {
        given:
        def smd = baseDef()
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.step('leaf', { Entity e, Ctx c, t -> } as Action)
            scope.operation('b', { OperationDef<Entity, Ctx> c -> c.run('leaf') })
            scope.operation('a', { OperationDef<Entity, Ctx> c ->
                c.operation('inner', { OperationDef<Entity, Ctx> nested -> nested.run('b') })
            })
        })

        when:
        smd.build()

        then:
        noExceptionThrown()
    }

    def 'a cycle inside a transition-attached container is rejected'() {
        given: 'the roots are every node, not only the ones registered at SM level'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', Ctx, { t ->
                t.operation('attached', { OperationDef<Entity, Ctx> c ->
                    c.operation('inner', { OperationDef<Entity, Ctx> nested -> nested.run('inner') })
                })
            }) })
            .state('s2', {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('inner')
    }

    def "a transition-attached container's id does not answer for a same-named SM-level one"() {
        given: 'nothing can name a transition-attached container, so it is on no cycle'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.step('leaf', { Entity e, Ctx c, t -> } as Action)
            scope.operation('a', { OperationDef<Entity, Ctx> c -> c.run('leaf') })
        })
        smd.state('s1', { s -> s.transitionsTo('s2', 't', Ctx, { t ->
            t.operation('a', { OperationDef<Entity, Ctx> c -> c.run('a') })
        }) })
        smd.state('s2', {})

        when: 'the inner run(a) reaches the acyclic SM-level container, not the attached one'
        smd.build()

        then:
        noExceptionThrown()
    }

    def "a transition-attached container's id does not hide a same-named SM-level cycle"() {
        given: 'the mirror case: the real container self-refs and must still be caught'
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
        smd.forContext(Ctx, { ContextScope<Entity, Ctx> scope ->
            scope.step('leaf', { Entity e, Ctx c, t -> } as Action)
            scope.operation('a', { OperationDef<Entity, Ctx> c -> c.run('a') })
        })
        smd.state('s1', { s -> s.transitionsTo('s2', 't', Ctx, { t ->
            t.operation('a', { OperationDef<Entity, Ctx> c -> c.run('leaf') })
        }) })
        smd.state('s2', {})

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('cycle')
        e.message.contains('a')
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1', { s -> s.transitionsTo('s2', 't', {}) })
            .state('s2', {})
        return smd
    }
}

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

package org.transflux.core.operation

import org.transflux.core.StateMachineDefImpl
import org.transflux.core.exception.TransfluxValidationException
import org.transflux.core.state.StateResolver
import org.transflux.core.transition.Transition
import spock.lang.Specification

import java.util.function.Function

class CompositeOperationDefStepMappingSpec extends Specification {

    static class Entity {
        String state
        List<String> trail = []

        Entity(String state) {
            this.state = state
        }
    }

    static class OrderCtx {
        String orderId
        BigDecimal amount
        String chargeResult
    }

    static class PaymentCtx {
        String reference
        BigDecimal cents
        String result
    }

    /** Reusable step that knows only PaymentCtx — never sees any parent. */
    static class ChargeStep implements Step<Entity, PaymentCtx> {
        @Override
        void execute(Entity entity, PaymentCtx context, Transition<Entity, PaymentCtx> transition) {
            context.result = 'charged-' + context.reference + '-' + context.cents
            entity.trail << ('charge:' + context.reference)
        }
    }

    static class OrderToPaymentMapper implements ContextMapper<OrderCtx, PaymentCtx> {
        @Override
        PaymentCtx mapTo(OrderCtx o) {
            def p = new PaymentCtx()
            p.reference = o.orderId
            p.cents = o.amount * 100
            return p
        }

        @Override
        void mapFrom(OrderCtx o, PaymentCtx p) {
            o.chargeResult = p.result
        }
    }

    def 'step-level mapping at call site via registered mapper id'() {
        given:
        def smd = baseDef()
        smd.step('charge', PaymentCtx, new ChargeStep())
            .mapper('order-to-payment', OrderCtx, PaymentCtx, new OrderToPaymentMapper())
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, OrderCtx> c ->
            c.usingContext(OrderCtx)
            c.step('charge', 'order-to-payment')
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new OrderCtx(orderId: 'ord-1', amount: 12.50)

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.chargeResult == 'charged-ord-1-1250.00'
        entity.trail == ['charge:ord-1']
    }

    def 'step-level mapping at call site via inline ContextMapper instance'() {
        given:
        def smd = baseDef()
        smd.step('charge', PaymentCtx, new ChargeStep())
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, OrderCtx> c ->
            c.usingContext(OrderCtx)
            c.step('charge', new OrderToPaymentMapper())
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new OrderCtx(orderId: 'ord-2', amount: 5.00)

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.chargeResult == 'charged-ord-2-500.00'
    }

    def 'step-level mapping at call site via inline Function (read-only)'() {
        given:
        def smd = baseDef()
        smd.step('charge', PaymentCtx, new ChargeStep())
        Function<OrderCtx, PaymentCtx> mapTo = { OrderCtx o ->
            def p = new PaymentCtx()
            p.reference = o.orderId
            p.cents = o.amount * 100
            return p
        }
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, OrderCtx> c ->
            c.usingContext(OrderCtx)
            c.step('charge', mapTo)
        })
        def sm = smd.build()
        def entity = new Entity('s1')
        def ctx = new OrderCtx(orderId: 'ord-3', amount: 3.00)

        when:
        def result = sm.entity(entity).transitionTo('s2', ctx)

        then:
        result.success
        ctx.chargeResult == null   // inline Function defaults mapFrom to no-op
        entity.trail == ['charge:ord-3']
    }

    def 'pass-through step ref with non-assignable component context is rejected at build'() {
        given:
        def smd = baseDef()
        smd.step('charge', PaymentCtx, new ChargeStep())
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, OrderCtx> c ->
            c.usingContext(OrderCtx)
            c.step('charge')   // no mapper, contexts don't match
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains("'charge'")
        e.message.contains('mapper')
    }

    def 'mapper P type incompatible with caller context is rejected at build'() {
        given:
        def smd = baseDef()
        smd.step('charge', PaymentCtx, new ChargeStep())
            .mapper('wrong-parent', Number, PaymentCtx, { Number n ->
                def p = new PaymentCtx()
                p.cents = n
                return p
            } as Function<Number, PaymentCtx>)
        smd.getTransition('t').compositeOperation('outer', { CompositeOperationDef<Entity, OrderCtx> c ->
            c.usingContext(OrderCtx)
            c.step('charge', 'wrong-parent')
        })

        when:
        smd.build()

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('wrong-parent')
    }

    private static StateMachineDefImpl<Entity> baseDef() {
        def smd = new StateMachineDefImpl<Entity>()
        smd.forEntityType(Entity)
            .withStateResolver({ e -> e.state } as StateResolver<Entity>)
            .state('s1').transitionsTo('s2', 't', OrderCtx)
            .state('s2')
        return smd
    }
}

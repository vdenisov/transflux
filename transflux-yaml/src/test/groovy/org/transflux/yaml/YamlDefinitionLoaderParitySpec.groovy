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

package org.transflux.yaml

import org.transflux.core.StateMachine
import org.transflux.core.trigger.DataTrigger
import org.transflux.core.trigger.EventTrigger
import org.transflux.core.trigger.ManualTrigger
import org.transflux.core.trigger.Trigger
import org.transflux.core.transition.ProcessResult
import org.transflux.core.transition.TransitionResult
import org.transflux.yaml.ParityMachine.Account
import org.transflux.yaml.ParityMachine.Activation
import spock.lang.Specification

/**
 * Drives one state machine written in each DSL - {@link ParityMachine} and the corpus document
 * declaring it again - through the same scenarios, and requires the two to agree on every result,
 * on the trigger catalog and metadata, and on what every listener saw.
 */
class YamlDefinitionLoaderParitySpec extends Specification {

    /** One delivery per listener and hook the machine declares, as the listeners record them. */
    static final List<String> LISTENED = [
        'state:ENTRY:active:Active:Billed and notified',
        'state:ENTRY:closed:null:null',
        'exit:draft:Draft:activate',
        'exit:suspended:null:reactivate',
        'transition:START:activate:Activate:manual-activate',
        'transition:COMPLETE:activate:Activate:manual-activate',
        'transition:START:terminate:null:null',
        'transition:ERROR:terminate:null:null',
        'action:START:charge:STEP',
        'action:COMPLETE:notification-flow:OPERATION',
        'action:ERROR:explode:STEP',
        'explode-error:IllegalStateException',
        'explode-error:IllegalArgumentException']

    def 'the Java and the YAML definition behave alike'() {
        when:
        Map<String, Object> java = drive(ParityMachine.definition().build())
        Map<String, Object> yaml = drive(LoaderFixtures.loadResource('parity/parity-machine.transflux.yml', Account)
            .build())

        then:
        yaml == java

        and: 'the scenarios reached what the machine declares, so two equally broken machines cannot agree'
        java.outcomes[0].executed == ['prepare', 'charge', 'notification-flow', 'notification-flow/render',
                                      'notification-flow/render/render-body', 'notification-flow/tier',
                                      'notification-flow/tier/premium-notice']
        java.outcomes[0].chargeId == 'charge-for-p7'
        java.outcomes[0..2]*.executed*.last() == ['notification-flow/tier/premium-notice',
                                                  'notification-flow/tier/standard-notice',
                                                  'notification-flow/tier/basic-notice']
        java.outcomes[3..7]*.transition == [null, 'suspend', 'reactivate', 'suspend', 'close']
        java.outcomes[8..10]*.compensated == [['reserve'], ['reserve'], ['reserve']]
        java.inline.findAll { it in ['route-undo', 'release', 'argument-undo'] } ==
            ['route-undo', 'release', 'argument-undo']
        java.forked.findAll { it.startsWith('audit:') } == ['audit:e', 'audit:p1', 'audit:p4', 'audit:p7']
        java.catalog.triggers*.id == ['manual-activate', 'overdue', 'closing']

        and: 'every listener category delivered'
        java.inline.containsAll(LISTENED)
    }

    /**
     * Runs every scenario against a machine and closes it, which waits for its forked branches.
     *
     * @param sm the machine
     *
     * @return what each scenario returned, the trigger catalog and metadata, and what the listeners
     *         and components recorded: in order on the driving thread, sorted from a branch
     */
    private static Map<String, Object> drive(StateMachine<Account> sm) {
        ParityMachine.INLINE.clear()
        ParityMachine.FORKED.clear()
        ParityMachine.driver = Thread.currentThread()
        List<Map<String, Object>> outcomes = []
        Map<String, Object> catalog
        try {
            // The shared manual trigger, taking each branch of the choice.
            [7, 4, 1].each { priority ->
                Activation context = new Activation("p${priority}")
                outcomes << describe(sm.entity(new Account(priority, null)).fire('manual-activate', context)) +
                    [chargeId: context.chargeId]
            }

            // The event filter refuses, then passes; the shared trigger fires from the other state;
            // the data gate holds.
            Account account = new Account(5, null)
            sm.entity(account).fire('manual-activate', new Activation('e'))
            outcomes << describe(sm.entity(account).processEvent('OVERDUE', 'EARLY'))
            outcomes << describe(sm.entity(account).processEvent('OVERDUE', 'LATE'))
            outcomes << describe(sm.entity(account).fire('manual-activate', new Activation('again')))
            outcomes << describe(sm.entity(account).processEvent('OVERDUE', 'LATE'))
            account.priority = -1
            outcomes << describe(sm.entity(account).processDataChange())

            // A guarded route, the fallback its guard leaves to, and a second route.
            ['boom', 'other', 'arg'].each { failure ->
                Account failing = new Account(1, failure)
                failing.state = 'active'
                outcomes << describe(sm.entity(failing).transitionTo('closed'))
            }

            catalog = [id         : sm.id,
                       name       : sm.name,
                       description: sm.description,
                       version    : sm.version,
                       triggers   : sm.triggers.collect { trigger ->
                           [id         : trigger.id,
                            name       : trigger.name,
                            description: trigger.description,
                            kind       : kind(trigger),
                            event      : trigger instanceof EventTrigger ? trigger.eventId : null,
                            transitions: trigger.transitionIds]
                       }]
        } finally {
            sm.close()
        }
        return [outcomes: outcomes, catalog: catalog, inline: ParityMachine.INLINE.toList(),
                forked: ParityMachine.FORKED.toList().sort()]
    }

    private static String kind(Trigger trigger) {
        return trigger instanceof ManualTrigger ? 'manual'
            : trigger instanceof EventTrigger ? 'event'
            : trigger instanceof DataTrigger ? 'data'
            : null
    }

    private static Map<String, Object> describe(TransitionResult<Account> result) {
        return [success    : result.success,
                source     : result.sourceStateId,
                target     : result.targetStateId,
                transition : result.transitionId,
                executed   : result.executedPath*.toString(),
                compensated: result.compensatedPath*.toString(),
                error      : result.error?.class?.simpleName]
    }

    private static Map<String, Object> describe(ProcessResult<Account> result) {
        return [fired: result.fired(), trigger: result.firedTriggerId()] +
            (result.result().map { describe(it) }.orElse([:]) as Map<String, Object>)
    }
}

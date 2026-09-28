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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.transflux.core.trigger.DataTrigger
import org.transflux.core.trigger.EventTrigger
import org.transflux.core.trigger.ManualTrigger
import spock.lang.Specification

import static org.transflux.yaml.LoaderFixtures.*

class StateMachineSectionSpec extends Specification {

    def setup() {
        TRAIL.clear()
    }

    def 'a whole state machine loads, builds and runs'() {
        given:
        def sm = loadResource('state-machine-section/whole-machine.transflux.yml').build()
        def order = new Order()

        when:
        def result = sm.entity(order).transitionTo('b')

        then:
        sm.id == 'orders'
        sm.name == 'Order Lifecycle'
        sm.description == 'What an order does'
        sm.version == '1.0.0'
        result.success
        order.state == 'b'
        // The runtime machine publishes no state or transition catalog, so the metadata each carries
        // is read off the payloads the listeners are handed.
        TRAIL == ['state-name:Start', 'transition-name:Start to end']

        cleanup:
        sm?.close()
    }

    def 'the state accessors may be classes instead of expressions'() {
        given:
        def sm = loadDocument(document(accessors: """\
            stateResolver:
              class: ${OrderResolver.name}
            stateApplier:
              class: ${OrderApplier.name}
            """)).build()
        def order = new Order()

        when:
        def result = sm.entity(order).transitionTo('b')

        then:
        result.success
        order.state == 'b'

        cleanup:
        sm?.close()
    }

    def 'a transition takes pre- and post-conditions in every descriptor form'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                conditions:
                  - id: registered
                    expression: 'priority > 5'
                """,
            transition: """\
                preConditions:
                  - registered
                  - id: inline-class
                    class: ${PriorityCondition.name}
                  - id: inline-predicate
                    predicate: ${PriorityBi.name}
                  - expression: 'priority > 5'
                postConditions:
                  - id: after
                    expression: 'priority > 0'
                """)).build()

        expect:
        sm.entity(new Order(priority: 9)).transitionTo('b').success
        !sm.entity(new Order(priority: 1)).transitionTo('b').success

        cleanup:
        sm?.close()
    }

    def 'a transition declaring a context types what is declared on it'() {
        given:
        def sm = loadDocument(document(transition: """\
            context: ${Ctx.name}
            preConditions:
              - id: typed
                predicate: ${CtxBi.name}
            listeners:
              onComplete:
                - id: typed-audit
                  class: ${CtxTransitionAudit.name}
            """)).build()

        when:
        def result = sm.entity(new Order()).transitionTo('b', new Ctx())

        then:
        result.success
        TRAIL == ['ctx-transition:COMPLETE']

        cleanup:
        sm?.close()
    }

    def 'triggers are referenced and declared in place, in all three kinds'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                triggers:
                  - id: registered-manual
                    type: manual
                """,
            transition: """\
                triggers:
                  - registered-manual
                  - id: inline-event
                    type: event
                    event: CONFIRMED
                    filter:
                      expression: "#event == 'ready'"
                  - id: inline-data
                    type: data
                    condition:
                      expression: 'priority > 5'
                """)).build()

        expect:
        sm.getTriggers(ManualTrigger)*.id == ['registered-manual']
        sm.getTriggers(EventTrigger)*.id == ['inline-event']
        sm.getTriggers(DataTrigger)*.id == ['inline-data']
        sm.getTrigger('registered-manual').transitionIds == ['t']

        sm.entity(new Order()).fire('registered-manual').success
        sm.entity(new Order()).processEvent('CONFIRMED', 'ready').fired()
        !sm.entity(new Order()).processEvent('CONFIRMED', 'not-ready').fired()
        sm.entity(new Order(priority: 9)).processDataChange().fired()
        !sm.entity(new Order(priority: 1)).processDataChange().fired()

        cleanup:
        sm?.close()
    }

    def 'one registered manual trigger sits on two transitions leaving different states'() {
        given:
        def sm = loadResource('state-machine-section/shared-manual-trigger.transflux.yml').build()

        expect:
        sm.getTrigger('cancel').transitionIds == ['a-to-cancelled', 'b-to-cancelled']
        sm.entity(new Order(state: 'a')).fire('cancel').transitionId == 'a-to-cancelled'
        sm.entity(new Order(state: 'b')).fire('cancel').transitionId == 'b-to-cancelled'

        cleanup:
        sm?.close()
    }

    def 'state and transition listeners attach by reference and in place, one serving several hooks'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                listeners:
                  - id: state-audit
                    class: ${StateAudit.name}
                """,
            states: """\
                states:
                  - id: a
                    listeners:
                      onExit:
                        - state-audit
                  - id: b
                    listeners:
                      onEntry:
                        - state-audit
                """,
            transition: """\
                listeners:
                  onStart:
                    - id: transition-audit
                      class: ${TransitionAudit.name}
                  onComplete:
                    - transition-audit
                """)).build()

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['transition:START', 'state:EXIT:a', 'transition:COMPLETE', 'state:ENTRY:b']

        cleanup:
        sm?.close()
    }

    def 'the onAny hooks take listeners of all three categories in one block'() {
        given:
        def definition = loadDocument(document(
            sections: """\
                steps:
                  - id: record
                    class: ${RecordingStep.name}

                listeners:
                  - id: state-audit
                    class: ${StateAudit.name}
                  - id: action-audit
                    class: ${ActionAudit.name}
                """,
            listeners: """\
                listeners:
                  onAnyStateEntry:
                    - state-audit
                  onAnyTransitionComplete:
                    - id: any-transition
                      class: ${TransitionAudit.name}
                  onAnyActionStart:
                    - action-audit
                """,
            transition: 'actions:\n  - run: record'))
        def sm = definition.build()

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == ['action:START:record', 'step', 'transition:COMPLETE', 'state:ENTRY:b']

        cleanup:
        sm?.close()
    }

    def 'a state and a transition each suppress the globals of their own category'() {
        given:
        def sm = loadDocument(document(
            sections: """\
                listeners:
                  - id: state-audit
                    class: ${StateAudit.name}
                  - id: transition-audit
                    class: ${TransitionAudit.name}
                """,
            listeners: """\
                listeners:
                  onAnyStateEntry:
                    - state-audit
                  onAnyTransitionComplete:
                    - transition-audit
                """,
            states: """\
                states:
                  - id: a
                  - id: b
                    disableGlobalListeners: [state-audit]
                """,
            transition: 'disableGlobalListeners: true\n')).build()

        when:
        sm.entity(new Order()).transitionTo('b')

        then:
        TRAIL == []

        cleanup:
        sm?.close()
    }

    def 'a transition without a target is refused where it is declared'() {
        when:
        loadDocument("""            apiVersion: transflux/v1

            stateMachine:
              entityType: ${Order.name}
              states:
                - id: a
              transitions:
                - id: t
                  from: a
            """)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml:8:7: state machine > transition 't': 'to' is required"
    }

    def 'a rejection names the line that caused it'() {
        when:
        loadDocument("""\
            apiVersion: transflux/v1

            stateMachine:
              entityType: ${Order.name}
              states:
                - id: a
                  onEntry: [nope]
            """)

        then:
        def e = thrown(DefinitionLoadException)
        e.message == "root.yml:7:7: state machine > state 'a': unknown key 'onEntry'; expected one of id, name, description, listeners, disableGlobalListeners"
    }

    def 'config.async asks for a pool, sized or at the default, even when nothing forks'() {
        given:
        def events = capture('org.transflux.execution.async')

        when:
        def sm = loadDocument(document(listeners: "config:\n  async: ${async}")).build()

        then:
        events.appender.list*.formattedMessage.any { it.startsWith(created) }

        cleanup:
        sm?.close()
        release(events)

        where:
        async                                           || created
        '\n    threadPoolSize: 2\n    queueCapacity: 4' || 'Async pool created, threads=2, queueCapacity=4'
        '{}'                                            || 'Async pool created, threads='
        '\n    onRejection: CALLER_RUNS'                || 'Async pool created, threads='
    }

    def 'config.async.onRejection answers for a fork the executor refuses'() {
        given:
        def sm = loadDocument(document(
            listeners: "config:\n  async: ${async}",
            transition: "actions:\n  - step: forked\n    class: ${RecordingStep.name}\n    fork: true")).build()
        // A closed pool refuses every submission, which leaves the policy to answer for it.
        sm.close()

        when:
        def result = sm.entity(new Order()).transitionTo('b')

        then:
        result.success == success

        where:
        async                      || success
        '{onRejection: FAIL}'      || false
        '{}'                       || true
    }

    def 'config.logging attaches the shipped trace, at the level and with the details asked for'() {
        given:
        def events = capture('org.transflux.trace')
        def sm = loadDocument(document(
            listeners: "config:\n  logging:\n    level: INFO${flags}",
            // Naming a global listener that is not there fails the build, so this builds only when the trace is attached.
            states: 'states:\n  - id: a\n    disableGlobalListeners: [transflux-log-state-exit]\n  - id: b')).build()

        when:
        sm.entity(new Order()).transitionTo('b', new StringBuilder('trace-marker'))
        def lines = events.appender.list*.formattedMessage

        then:
        !lines.isEmpty()
        events.appender.list*.level.every { it == Level.INFO }
        lines.any { it.contains('durationMs=') } == details
        lines.any { it.contains('context=trace-marker') } == details

        cleanup:
        sm?.close()
        release(events)

        where:
        flags                                                  || details
        '\n    includeContext: true\n    includeTimings: true' || true
        ''                                                     || false
    }

    def 'refuses #what'() {
        when:
        loadDocument(document(parts))

        then:
        def e = thrown(DefinitionLoadException)
        e.declarationPath() == path
        e.problem().startsWith(problem)

        where:
        what                                 | parts                                                                                                    || path                                               | problem
        'a context on an inline trigger'     | [transition: "triggers:\n  - id: inline\n    type: manual\n    context: ${Ctx.name}"]                    || "state machine > transition 't' > trigger 'inline'" | "unknown key 'context'"
        'a resolver written as both forms'   | [accessors: "stateResolver:\n  class: ${OrderResolver.name}\n  expression: 'state'"]                     || 'state machine'                                     | "exactly one of 'class', 'expression' is allowed"
        'a resolver for another entity'      | [accessors: "stateResolver:\n  class: ${StringResolver.name}"]                                           || 'state machine'                                     | "class ${StringResolver.name} declares"
        'an async key with no value'         | [listeners: 'config:\n  async:']                                                                     || 'state machine'                                     | "'async' requires a value; write 'async: {}' for the default pool"
        'a logging key with no value'        | [listeners: 'config:\n  logging:']                                                                   || 'state machine'                                     | "'logging' requires a value; write 'logging: {}' for the default trace"
        'a pool sized by one number'         | [listeners: 'config:\n  async:\n    threadPoolSize: 2']                                                  || 'state machine'                                     | "'threadPoolSize' and 'queueCapacity' are given together or not at all"
        'a pool sized by the other'          | [listeners: 'config:\n  async:\n    queueCapacity: 4']                                                   || 'state machine'                                     | "'threadPoolSize' and 'queueCapacity' are given together or not at all"
        'a pool of no threads'               | [listeners: 'config:\n  async:\n    threadPoolSize: 0\n    queueCapacity: 4']                             || 'state machine'                                     | 'Async pool thread count must be positive'
        'an unknown rejection policy'        | [listeners: 'config:\n  async:\n    onRejection: RETRY']                                                 || 'state machine'                                     | "'onRejection' must be one of DROP, FAIL, BLOCK, CALLER_RUNS"
        'an unknown async key'               | [listeners: 'config:\n  async:\n    threads: 2']                                                         || 'state machine'                                     | "unknown key 'threads'"
        'an unknown trace level'             | [listeners: 'config:\n  logging:\n    level: LOUD']                                                        || 'state machine'                                     | "'level' must be one of ERROR, WARN, INFO, DEBUG, TRACE"
        'an unknown logging key'             | [listeners: 'config:\n  logging:\n    format: json']                                                       || 'state machine'                                     | "unknown key 'format'"
        'metrics, which nothing reads'       | [listeners: 'config:\n  metrics:\n    enabled: true']                                                      || 'state machine'                                     | "unknown key 'metrics'"
        'a typed listener on a global hook'  | [listeners: "listeners:\n  onAnyTransitionComplete:\n    - id: typed\n      class: ${CtxTransitionAudit.name}"] || "state machine > listener 'typed'"             | "class ${CtxTransitionAudit.name} declares"
    }

    /**
     * Composes a root document of states {@code a} and {@code b} and a transition {@code t}.
     *
     * @param parts any of {@code sections} (above {@code stateMachine:}), {@code accessors},
     *        {@code listeners} (the state machine's own keys), {@code states} and
     *        {@code transition} (what the transition declares beyond its endpoints)
     *
     * @return the document
     */
    private static String document(Map<String, String> parts) {
        String accessors = parts.accessors ?: "stateResolver:\n  expression: 'state'\nstateApplier:\n  expression: 'state'"
        String states = parts.states ?: 'states:\n  - id: a\n  - id: b'
        String transition = parts.transition == null ? '' : indent(parts.transition, 6) + '\n'
        return 'apiVersion: transflux/v1\n\n' +
            (parts.sections ? parts.sections.stripIndent().trim() + '\n\n' : '') +
            'stateMachine:\n' +
            "  entityType: ${Order.name}\n" +
            indent(accessors, 2) + '\n' +
            (parts.listeners ? indent(parts.listeners, 2) + '\n' : '') +
            indent(states, 2) + '\n' +
            '  transitions:\n    - id: t\n      from: a\n      to: b\n' +
            transition
    }

    /** Captures everything on {@code name} until {@link #release}, which restores the level it had. */
    private static Map capture(String name) {
        Logger logger = (Logger) LoggerFactory.getLogger(name)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logger.addAppender(appender)
        Map captured = [logger: logger, appender: appender, level: logger.level]
        logger.level = Level.TRACE
        return captured
    }

    private static void release(Map captured) {
        captured.logger.detachAppender(captured.appender)
        captured.logger.level = captured.level
    }

    private static String indent(String text, int spaces) {
        return text.stripIndent().trim().readLines().collect { ' ' * spaces + it }.join('\n')
    }
}

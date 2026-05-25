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

import org.slf4j.Logger
import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class InstanceOrClassSourceSpec extends Specification {

    static interface Widget {}

    static class WidgetImpl implements Widget {
        String tag = 'reflective'
    }

    static class NeedsArgs implements Widget {
        NeedsArgs(String required) {}
    }

    Logger log = Mock(Logger)

    InstanceOrClassSource<Widget> newSource() {
        new InstanceOrClassSource<>(log, 'Widget source', "WidgetDef 'w1'")
    }

    def 'resolve on an empty source throws naming the kind and owner'() {
        given:
        def source = newSource()

        when:
        source.resolve('Widget')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "WidgetDef 'w1' has no widget set; call using(...) before build"
    }

    def 'setInstance then resolve returns the held instance'() {
        given:
        def source = newSource()
        def held = new WidgetImpl()

        when:
        source.setInstance(held)

        then:
        source.isSet()
        source.resolve('Widget').is(held)
        0 * log.warn(_, _, _)
    }

    def 'setClass then resolve reflectively instantiates via the no-arg constructor'() {
        given:
        def source = newSource()

        when:
        source.setClass(WidgetImpl)
        def resolved = source.resolve('Widget')

        then:
        source.isSet()
        resolved instanceof WidgetImpl
        resolved.tag == 'reflective'
        0 * log.warn(_, _, _)
    }

    def 'setInstance after setInstance warns and replaces (last-write-wins)'() {
        given:
        def source = newSource()
        def first = new WidgetImpl(tag: 'first')
        def second = new WidgetImpl(tag: 'second')

        when:
        source.setInstance(first)
        source.setInstance(second)

        then:
        1 * log.warn('{} already defined for {}; overriding previous value', 'Widget source', "WidgetDef 'w1'")
        source.resolve('Widget').is(second)
    }

    def 'setInstance after setClass warns and replaces'() {
        given:
        def source = newSource()
        def instance = new WidgetImpl(tag: 'replaced-class')

        when:
        source.setClass(WidgetImpl)
        source.setInstance(instance)

        then:
        1 * log.warn('{} already defined for {}; overriding previous value', 'Widget source', "WidgetDef 'w1'")
        source.resolve('Widget').is(instance)
    }

    def 'setClass after setInstance warns and replaces'() {
        given:
        def source = newSource()

        when:
        source.setInstance(new WidgetImpl(tag: 'going-away'))
        source.setClass(WidgetImpl)

        then:
        1 * log.warn('{} already defined for {}; overriding previous value', 'Widget source', "WidgetDef 'w1'")
        source.resolve('Widget') instanceof WidgetImpl
    }

    def 'clear empties both slots without logging'() {
        given:
        def source = newSource()
        source.setInstance(new WidgetImpl())

        when:
        source.clear()

        then:
        !source.isSet()
        0 * log.warn(_, _, _)
    }

    def 'setInstance after clear does not warn'() {
        given:
        def source = newSource()
        source.setInstance(new WidgetImpl(tag: 'first'))
        source.clear()

        when:
        source.setInstance(new WidgetImpl(tag: 'fresh'))

        then:
        0 * log.warn(_, _, _)
        source.isSet()
    }

    def 'resolve propagates instantiateNoArg failure when class has no no-arg constructor'() {
        given:
        def source = newSource()
        source.setClass(NeedsArgs)

        when:
        source.resolve('Widget')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.startsWith("Widget class '")
        e.message.endsWith("' has no accessible no-arg constructor")
    }

    def 'resolve lower-cases the supplied kind label in the missing-source message'() {
        given:
        def source = new InstanceOrClassSource<>(log, 'Mapper source', "MapperDef 'm1'")

        when:
        source.resolve('ContextMapper')

        then:
        def e = thrown(TransfluxValidationException)
        e.message == "MapperDef 'm1' has no contextmapper set; call using(...) before build"
    }

    def 'static resolve returns the instance when non-null'() {
        given:
        def held = new WidgetImpl()

        expect:
        InstanceOrClassSource.resolve(held, WidgetImpl, 'Widget').is(held)
    }

    def 'static resolve instantiates the class when instance is null'() {
        when:
        def resolved = InstanceOrClassSource.resolve((Widget) null, WidgetImpl, 'Widget')

        then:
        resolved instanceof WidgetImpl
    }
}

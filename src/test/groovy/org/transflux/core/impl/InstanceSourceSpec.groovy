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

class InstanceSourceSpec extends Specification {

    static interface Widget {}

    static class WidgetImpl implements Widget {
        String tag = 'held'
    }

    Logger log = Mock(Logger)

    InstanceSource<Widget> newSource() {
        new InstanceSource<>(log, 'Widget source', "WidgetDef 'w1'")
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

    def 'resolve lower-cases the supplied kind label in the missing-source message'() {
        given:
        def source = newSource()

        when:
        source.resolve('Compensation')

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('has no compensation set')
    }

    def 'setInstance then resolve returns the held instance'() {
        given:
        def source = newSource()
        def held = new WidgetImpl()

        when:
        source.setInstance(held)

        then:
        source.resolve('Widget').is(held)
    }

    def 'setInstance twice warns and keeps the later value'() {
        given:
        def source = newSource()
        def first = new WidgetImpl()
        def second = new WidgetImpl()

        when:
        source.setInstance(first)
        source.setInstance(second)

        then: 'the overwrite is reported against the owner, and the last write wins'
        1 * log.warn('Definition value overwritten, field={}, owner={}', 'Widget source', "WidgetDef 'w1'")
        source.resolve('Widget').is(second)
    }

    def 'the first setInstance does not warn'() {
        given:
        def source = newSource()

        when:
        source.setInstance(new WidgetImpl())

        then:
        0 * log.warn(_, _, _)
    }

    def 'isSet reports whether a value was declared'() {
        given:
        def source = newSource()

        expect:
        !source.isSet()

        when:
        source.setInstance(new WidgetImpl())

        then:
        source.isSet()
    }

    def 'resolveOptional answers null for a slot nothing declared'() {
        expect: 'an optional slot - a compensation, say - stays empty rather than throwing'
        newSource().resolveOptional('Compensation') == null
    }

    def 'resolveOptional answers the held value once one is declared'() {
        given:
        def source = newSource()
        def held = new WidgetImpl()
        source.setInstance(held)

        expect:
        source.resolveOptional('Widget').is(held)
    }
}

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

package org.transflux.core.transition

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification
import spock.lang.Unroll

class StepPathSpec extends Specification {

    def 'of(segments) builds an immutable path with the supplied segments'() {
        when:
        def path = StepPath.of('outer', 'inner', 'leaf')

        then:
        path.segments() == ['outer', 'inner', 'leaf']
    }

    def 'of with a single segment yields a top-level path'() {
        when:
        def path = StepPath.of('only')

        then:
        path.segments() == ['only']
        path.isTopLevel()
    }

    def 'returned segments list is unmodifiable'() {
        given:
        def path = StepPath.of('a', 'b')

        when:
        path.segments().add('intruder')

        then:
        thrown(UnsupportedOperationException)
    }

    def 'constructor rejects an empty segment list'() {
        when:
        new StepPath([])

        then:
        def e = thrown(TransfluxValidationException)
        e.message.contains('must not be empty')
    }

    def 'constructor rejects a null segment list'() {
        when:
        new StepPath(null)

        then:
        def e = thrown(TransfluxValidationException)
        e.message.toLowerCase().contains('segments')
    }

    @Unroll
    def 'constructor rejects blank segment "#segment" in segment list'() {
        when:
        StepPath.of('valid', segment)

        then:
        thrown(TransfluxValidationException)

        where:
        segment << ['', '  ', '\t']
    }

    def 'constructor rejects a null segment in segment list'() {
        // StepPath.of(...) delegates to List.of(...) which itself rejects null with NPE before
        // the validating constructor runs. Build the list directly to exercise the constructor's
        // own requireNotBlank guard on per-segment null.
        when:
        new StepPath(['valid', null])

        then:
        thrown(TransfluxValidationException)
    }

    def 'leaf() returns the last segment'() {
        expect:
        StepPath.of('only').leaf() == 'only'
        StepPath.of('outer', 'inner').leaf() == 'inner'
        StepPath.of('a', 'b', 'c', 'd').leaf() == 'd'
    }

    @Unroll
    def 'depth() returns segments.size() - 1 for #segments'() {
        expect:
        new StepPath(segments).depth() == expectedDepth

        where:
        segments                 || expectedDepth
        ['only']                 || 0
        ['outer', 'inner']       || 1
        ['a', 'b', 'c']          || 2
        ['a', 'b', 'c', 'd', 'e'] || 4
    }

    @Unroll
    def 'isTopLevel() distinguishes single-segment from nested paths (#segments)'() {
        expect:
        new StepPath(segments).isTopLevel() == expected

        where:
        segments               || expected
        ['only']               || true
        ['outer', 'leaf']      || false
        ['a', 'b', 'c']        || false
    }

    def 'append(segment) returns a new path with the segment added at the end'() {
        given:
        def original = StepPath.of('outer', 'inner')

        when:
        def extended = original.append('leaf')

        then:
        extended.segments() == ['outer', 'inner', 'leaf']
        // Original is unchanged.
        original.segments() == ['outer', 'inner']
    }

    def 'append(null) and append(blank) are rejected'() {
        given:
        def path = StepPath.of('a')

        when:
        path.append(segment)

        then:
        thrown(TransfluxValidationException)

        where:
        segment << [null, '', '  ']
    }

    @Unroll
    def 'toString() joins segments with "/" for #segments'() {
        expect:
        new StepPath(segments).toString() == expected

        where:
        segments               || expected
        ['only']               || 'only'
        ['outer', 'inner']     || 'outer/inner'
        ['a', 'b', 'c', 'd']   || 'a/b/c/d'
    }

    def 'records with equal segments are equal and share a hash code'() {
        given:
        def a = StepPath.of('outer', 'inner')
        def b = StepPath.of('outer', 'inner')
        def c = StepPath.of('outer', 'other')

        expect:
        a == b
        a.hashCode() == b.hashCode()
        a != c
    }
}

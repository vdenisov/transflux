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

import org.transflux.core.exception.TransfluxValidationException
import spock.lang.Specification

class DefinitionLoadExceptionSpec extends Specification {

    def 'the message leads with whatever of the location is known'() {
        given:
        def e = new DefinitionLoadException(identifier, location, line, column, path, 'broken', null)

        expect:
        e.message == message
        e instanceof TransfluxValidationException

        where:
        identifier | location   | line | column | path                              || message
        'a.yml'    | null       | null | null   | null                              || 'a.yml: broken'
        'a.yml'    | 'a.yml'    | 3    | 5      | null                              || 'a.yml:3:5: broken'
        'a.yml'    | '/x/a.yml' | 3    | 5      | "transition 't' > operation 'op'" || "a.yml (/x/a.yml):3:5: transition 't' > operation 'op': broken"
    }

    def 'every part is available on its own'() {
        given:
        def cause = new IllegalStateException()
        def e = new DefinitionLoadException('a.yml', '/x/a.yml', 3, 5, "state 's'", 'broken', cause)

        expect:
        e.identifier() == 'a.yml'
        e.location() == '/x/a.yml'
        e.line() == 3
        e.column() == 5
        e.declarationPath() == "state 's'"
        e.problem() == 'broken'
        e.cause.is(cause)
    }
}

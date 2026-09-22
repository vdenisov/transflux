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

package org.transflux.yaml;

import org.transflux.core.exception.TransfluxValidationException;

/**
 * A definition document the loader refused, located in the document that declared the problem. The
 * message leads with the location, {@code identifier:line:column: declaration path: problem}, and
 * the parts are also available one by one.
 */
public class DefinitionLoadException extends TransfluxValidationException {

    private final String identifier;
    private final String location;
    private final Integer line;
    private final Integer column;
    private final String declarationPath;
    private final String problem;

    DefinitionLoadException(String identifier, String location, Integer line, Integer column,
                            String declarationPath, String problem, Throwable cause) {
        super(format(identifier, location, line, column, declarationPath, problem), cause);
        this.identifier = identifier;
        this.location = location;
        this.line = line;
        this.column = column;
        this.declarationPath = declarationPath;
        this.problem = problem;
    }

    /**
     * @return the identifier of the document the problem is in, as it was asked of the source
     */
    public String identifier() {
        return identifier;
    }

    /**
     * @return where the source found that document, or {@code null} when it did not say
     */
    public String location() {
        return location;
    }

    /**
     * @return the 1-based line, or {@code null} when the problem is with the document as a whole
     */
    public Integer line() {
        return line;
    }

    /**
     * @return the 1-based column, or {@code null} when the problem is with the document as a whole
     */
    public Integer column() {
        return column;
    }

    /**
     * @return the enclosing declarations, such as {@code transition 't' > operation 'op'}, or
     *         {@code null} when the problem sits outside any declaration
     */
    public String declarationPath() {
        return declarationPath;
    }

    /**
     * @return what was wrong, without the location that leads the message
     */
    public String problem() {
        return problem;
    }

    private static String format(String identifier, String location, Integer line, Integer column,
                                 String declarationPath, String problem) {
        StringBuilder message = new StringBuilder(identifier);
        if (location != null && !location.equals(identifier)) {
            message.append(" (").append(location).append(')');
        }
        if (line != null) {
            message.append(':').append(line).append(':').append(column);
        }
        message.append(": ");
        if (declarationPath != null) {
            message.append(declarationPath).append(": ");
        }
        return message.append(problem).toString();
    }
}

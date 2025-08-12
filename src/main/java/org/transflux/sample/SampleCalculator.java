/*
 * Copyright 2025 Victor Denisov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.transflux.sample;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO: Remove after initial build verification
 */
public class SampleCalculator {
    private static final Logger log = LoggerFactory.getLogger(SampleCalculator.class);

    public int add(int a, int b) {
        log.info("Adding numbers: a={}, b={}", a, b);
        int result = a + b;
        log.info("Addition result: {}", result);
        return result;
    }

    public int multiply(int a, int b) {
        log.info("Multiplying numbers: a={}, b={}", a, b);
        int result = a * b;
        log.info("Multiplication result: {}", result);
        return result;
    }
}

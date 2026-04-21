/**
 * Copyright 2022 N5 Technologies, Inc
 *
 * This product includes software developed at N5 Technologies, Inc
 * (http://www.n5corp.com/) as well as software licenced to N5 Technologies,
 * Inc under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding
 * copyright ownership.
 *
 * N5 Technologies licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.neeve.appbuilder;

import java.util.Arrays;
import java.util.stream.Collectors;

class TokenUtils {
    static final String TOKEN_PREFIX = "{{";
    static final String TOKEN_SUFFIX = "}}";

    static String toToken(String name) {
        return TOKEN_PREFIX + name + TOKEN_SUFFIX;
    }

    static String toKebabCase(String name) {
        return name
                .replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("[\\s_]+", "-")
                .toLowerCase();
    }

    static String toSlashCase(String kebabCase) {
        return kebabCase.replace("-", "/");
    }

    static String toPascalCase(String input) {
        return Arrays.stream(input.split("[-\\s_]+"))
                     .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
                     .collect(Collectors.joining());
    }

    static String toPackagePath(String kebabCase) {
        return kebabCase.replace("-", ".");
    }

    static String forDisplay(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }

        String[] words = name.trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                result.append(word.substring(1));
                result.append(" ");
            }
        }
        return result.toString().trim();
    }
}


/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.claude;

import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountRequestDecorator;
import org.jspecify.annotations.Nullable;

/**
 * How an account signs the command line in, read from its connection: the header an API key or a token decorates a
 * request with, and no header for the login of the command line itself.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
record ClaudeCredential(ClaudeAuthMode mode, @Nullable String secret) {
    static final String API_KEY_HEADER = "x-api-key";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    static ClaudeCredential of(AccountConnection connection) {
        String[] found = new String[2];
        connection.decorate(new AccountRequestDecorator() {
            @Override
            public void header(String name, String value) {
                if (API_KEY_HEADER.equalsIgnoreCase(name)) {
                    found[0] = value;
                }
                else if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                    found[1] = value.startsWith(BEARER) ? value.substring(BEARER.length()) : value;
                }
            }

            @Override
            public void queryParameter(String name, String value) {
            }
        });
        if (found[0] != null) {
            return new ClaudeCredential(ClaudeAuthMode.API_KEY, found[0]);
        }
        if (found[1] != null) {
            return new ClaudeCredential(ClaudeAuthMode.OAUTH_TOKEN, found[1]);
        }
        return new ClaudeCredential(ClaudeAuthMode.CLI, null);
    }
}

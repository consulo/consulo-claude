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

import consulo.ai.AIProvider;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * How the command line is signed in - its own login, an API key or an OAuth token, each passed its own way. Instances
 * from before accounts kept it as the {@value #OPTION} option, which {@link ClaudeAIProviderType#migrateToAccount}
 * reads.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
enum ClaudeAuthMode {
    /**
     * The login of the command line itself, {@code claude auth login}.
     */
    CLI("cli", ClaudeLocalize.authModeCli(), null),
    /**
     * An API key of the Anthropic Console, billed per use.
     */
    API_KEY("apiKey", ClaudeLocalize.authModeApiKey(), "ANTHROPIC_API_KEY"),
    /**
     * A long-lived token of {@code claude setup-token}, which uses the Claude subscription.
     */
    OAUTH_TOKEN("oauthToken", ClaudeLocalize.authModeOauthToken(), "CLAUDE_CODE_OAUTH_TOKEN");

    static final String OPTION = "auth";

    private final String myId;
    private final LocalizeValue myDisplayName;
    private final @Nullable String myEnvironmentVariable;

    ClaudeAuthMode(String id, LocalizeValue displayName, @Nullable String environmentVariable) {
        myId = id;
        myDisplayName = displayName;
        myEnvironmentVariable = environmentVariable;
    }

    LocalizeValue getDisplayName() {
        return myDisplayName;
    }

    /**
     * Where the secret of the account goes - null when the mode does not use it.
     */
    @Nullable String getEnvironmentVariable() {
        return myEnvironmentVariable;
    }

    static ClaudeAuthMode of(AIProvider provider) {
        return of(provider.getOptions());
    }

    static ClaudeAuthMode of(Map<String, String> options) {
        String id = options.get(OPTION);
        for (ClaudeAuthMode mode : values()) {
            if (mode.myId.equals(id)) {
                return mode;
            }
        }
        return CLI;
    }
}

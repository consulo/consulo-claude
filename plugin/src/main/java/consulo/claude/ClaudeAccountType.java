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

import consulo.accountManagement.AccountAuthorizeConfig;
import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountDetails;
import consulo.accountManagement.AccountType;
import consulo.accountManagement.NoScope;
import consulo.accountManagement.authorize.ApiKeyAuthorizeConfig;
import consulo.accountManagement.authorize.TokenAuthorizeConfig;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.claude.icon.ClaudeIconGroup;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.image.Image;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * An account of Claude - the login of the claude command line, an API key of the Anthropic Console, or a long-lived
 * OAuth token of {@code claude setup-token} for a Claude subscription.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
@ExtensionImpl
public class ClaudeAccountType implements AccountType<NoScope> {
    public static final String ID = "claude";

    static final ApiKeyAuthorizeConfig API_KEY = new ApiKeyAuthorizeConfig() {
        @Override
        public @Nullable String getHeaderName() {
            return ClaudeCredential.API_KEY_HEADER;
        }
    };

    static final TokenAuthorizeConfig OAUTH_TOKEN = new TokenAuthorizeConfig() {
    };

    private final ApplicationConcurrency myApplicationConcurrency;

    @Inject
    public ClaudeAccountType(ApplicationConcurrency applicationConcurrency) {
        myApplicationConcurrency = applicationConcurrency;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getName() {
        return ClaudeLocalize.accountTypeName();
    }

    @Override
    public Image getIcon() {
        return ClaudeIconGroup.claude();
    }

    @Override
    public Class<NoScope> getScopeClass() {
        return NoScope.class;
    }

    @Override
    public @Nullable String getDefaultServerUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public List<? extends AccountAuthorizeConfig> getAuthorizeConfigs() {
        return List.of(ClaudeCliAuthorizeConfig.INSTANCE, API_KEY, OAUTH_TOKEN);
    }

    /**
     * The login of the command line says who it is; a key or a token says only what it is - finding out the user it
     * belongs to would cost a request.
     */
    @Override
    public CompletableFuture<AccountDetails> loadDetails(AccountConnection connection) {
        ClaudeCredential credential = ClaudeCredential.of(connection);
        return switch (credential.mode()) {
            case API_KEY -> CompletableFuture.completedFuture(new AccountDetails(ClaudeLocalize.accountNameApiKey().get(), null, null));
            case OAUTH_TOKEN -> CompletableFuture.completedFuture(new AccountDetails(ClaudeLocalize.accountNameOauthToken().get(), null, null));
            case CLI -> CompletableFuture.supplyAsync(ClaudeCli::status, myApplicationConcurrency.executor()).thenApply(status -> {
                String account = status.account();
                return new AccountDetails(account == null ? ClaudeLocalize.accountNameCli().get() : account, account, null);
            });
        };
    }
}

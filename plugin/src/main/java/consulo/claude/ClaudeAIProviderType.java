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

import consulo.ai.AIContent;
import consulo.ai.AIMessage;
import consulo.ai.AIModel;
import consulo.ai.AIProvider;
import consulo.ai.AIProviderType;
import consulo.ai.AIRequest;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStreamListener;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.claude.icon.ClaudeIconGroup;
import consulo.claude.localize.ClaudeLocalize;
import consulo.disposer.Disposable;
import consulo.localize.LocalizeValue;
import consulo.platform.Platform;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.ui.Component;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude through the claude command line: it signs in on its own, keeps the conversation on its side and runs its own
 * tools, so an instance needs no API key here.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
@ExtensionImpl
@Singleton
public class ClaudeAIProviderType implements AIProviderType {
    public static final String ID = "claude";

    private static final List<AIModel> MODELS = List.of(
        new ClaudeModel("opus", "Claude Opus", 200_000),
        new ClaudeModel("sonnet", "Claude Sonnet", 200_000),
        new ClaudeModel("haiku", "Claude Haiku", 200_000)
    );

    private final ProcessHandlerBuilderFactory myProcessHandlerBuilderFactory;
    private final ApplicationConcurrency myApplicationConcurrency;
    private final Map<String, ClaudeCliSession> mySessions = new ConcurrentHashMap<>();

    @Inject
    public ClaudeAIProviderType(ProcessHandlerBuilderFactory processHandlerBuilderFactory, ApplicationConcurrency applicationConcurrency) {
        myProcessHandlerBuilderFactory = processHandlerBuilderFactory;
        myApplicationConcurrency = applicationConcurrency;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return ClaudeLocalize.providerName();
    }

    @Override
    public Image getIcon() {
        return ClaudeIconGroup.claude();
    }

    @Override
    public List<AIModel> getModels() {
        return MODELS;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public boolean acceptsApiKey() {
        return true;
    }

    @Override
    public LocalizeValue getApiKeyLabel() {
        return ClaudeLocalize.labelApiKey();
    }

    @RequiredUIAccess
    @Override
    public @Nullable Component createAccountComponent(Map<String, String> options, Disposable uiDisposable) {
        return new ClaudeAccountComponent(myProcessHandlerBuilderFactory, myApplicationConcurrency, options).getComponent();
    }

    /**
     * Configured when the command line is there and, unless it signs in on its own, the account has its secret -
     * whether the command line login itself is valid shows on the first answer, which carries the reason.
     */
    @Override
    public CompletableFuture<Boolean> isConfigured(AIProvider provider) {
        if (ClaudeAuthMode.of(provider) == ClaudeAuthMode.CLI) {
            return CompletableFuture.supplyAsync(() -> ClaudeCli.findExecutable() != null, myApplicationConcurrency.executor());
        }
        return provider.getApiKey().thenApply(secret -> secret != null && ClaudeCli.findExecutable() != null);
    }

    @Override
    public CompletableFuture<AIResponse> chat(AIProvider provider, AIRequest request, @Nullable AIStreamListener listener) {
        String text = lastUserText(request);
        if (text == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Nothing to send - the request has no user text"));
        }

        String sessionId = request.getSessionId();
        return provider.getApiKey().thenApplyAsync(apiKey -> {
            try {
                return sessionId == null ? start(provider, request, apiKey) : session(provider, sessionId, request, apiKey);
            }
            catch (ExecutionException | IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, myApplicationConcurrency.executor()).thenCompose(session -> {
            CompletableFuture<AIResponse> answer = session.send(text, listener);
            if (sessionId == null) {
                answer.whenComplete((response, error) -> session.close());
            }
            return answer;
        });
    }

    @Override
    public void closeSession(AIProvider provider, String sessionId) {
        ClaudeCliSession session = mySessions.remove(key(provider, sessionId));
        if (session != null) {
            session.close();
        }
    }

    private ClaudeCliSession session(AIProvider provider, String sessionId, AIRequest request, @Nullable String apiKey)
        throws ExecutionException, IOException {
        String key = key(provider, sessionId);
        ClaudeCliSession existing = mySessions.get(key);
        if (existing != null) {
            return existing;
        }
        ClaudeCliSession session = start(provider, request, apiKey);
        ClaudeCliSession raced = mySessions.putIfAbsent(key, session);
        if (raced != null) {
            session.close();
            return raced;
        }
        return session;
    }

    private ClaudeCliSession start(AIProvider provider, AIRequest request, @Nullable String apiKey) throws ExecutionException, IOException {
        Path executable = ClaudeCli.findExecutable();
        if (executable == null) {
            throw new IOException("The claude command line was not found on the PATH");
        }
        ClaudeModel model = request.getModel() instanceof ClaudeModel claudeModel ? claudeModel : (ClaudeModel) MODELS.getFirst();
        ClaudeAuthMode authMode = ClaudeAuthMode.of(provider);
        if (authMode != ClaudeAuthMode.CLI && (apiKey == null || apiKey.isBlank())) {
            throw new IOException("The account signs in with " + authMode.getDisplayName().get() + ", but it has none set");
        }
        Path workingDirectory = request.getWorkingDirectory();
        return ClaudeCliSession.start(
            myProcessHandlerBuilderFactory,
            executable,
            model,
            workingDirectory != null ? workingDirectory : Platform.current().user().homePath(),
            authMode,
            apiKey,
            ClaudePermissionMode.of(provider)
        );
    }

    private static String key(AIProvider provider, String sessionId) {
        return provider.getName() + "/" + sessionId;
    }

    private static @Nullable String lastUserText(AIRequest request) {
        List<AIMessage> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AIMessage message = messages.get(i);
            if (message.role() != AIRole.USER) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (AIContent content : message.content()) {
                if (content instanceof AIContent.Text part) {
                    text.append(part.text());
                }
            }
            return text.isEmpty() ? null : text.toString();
        }
        return null;
    }
}

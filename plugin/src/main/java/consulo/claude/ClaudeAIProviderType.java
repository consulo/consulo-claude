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

import consulo.accountManagement.Account;
import consulo.accountManagement.AccountAuthorizeConfig;
import consulo.accountManagement.AccountAuthorizeType;
import consulo.accountManagement.AccountCredentials;
import consulo.accountManagement.AccountManager;
import consulo.accountManagement.AccountType;
import consulo.ai.AIChatCarryover;
import consulo.ai.AIChatInfo;
import consulo.ai.AIChatSessionListener;
import consulo.ai.AIContent;
import consulo.ai.AIMessage;
import consulo.ai.AIModel;
import consulo.ai.AIProvider;
import consulo.ai.AIProviderTypeWithChats;
import consulo.ai.AIRequest;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStreamListener;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.claude.icon.ClaudeIconGroup;
import consulo.claude.localize.ClaudeLocalize;
import consulo.disposer.Disposable;
import consulo.disposer.Disposer;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.project.Project;
import consulo.ui.Component;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.image.Image;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Claude through the claude command line: it signs in on its own, keeps the conversation on its side and runs its own
 * tools, so an instance needs no API key here.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
@ExtensionImpl
@Singleton
public class ClaudeAIProviderType implements AIProviderTypeWithChats {
    public static final String ID = "claude";

    private static final Logger LOG = Logger.getInstance(ClaudeAIProviderType.class);

    /**
     * Until the command line listed its models - the aliases it always knows.
     */
    private static final List<AIModel> FALLBACK_MODELS = List.of(
        new ClaudeModel(ClaudeModel.DEFAULT_ID, "Default", -1),
        new ClaudeModel("opus", "Opus", -1),
        new ClaudeModel("sonnet", "Sonnet", -1),
        new ClaudeModel("haiku", "Haiku", -1)
    );

    /**
     * As much of the conversation as the command line itself titles a session from.
     */
    private static final int TITLE_CONTEXT_LENGTH = 1000;

    private final ProcessHandlerBuilderFactory myProcessHandlerBuilderFactory;
    private final ApplicationConcurrency myApplicationConcurrency;
    private final AccountManager myAccountManager;
    private final Map<String, ClaudeCliSession> mySessions = new ConcurrentHashMap<>();
    /**
     * The session of the command line a chat goes on in, when it started over - by the chat.
     */
    private final Map<String, String> myAliases = new ConcurrentHashMap<>();
    private final Map<String, List<AIChatSessionListener>> mySessionListeners = new ConcurrentHashMap<>();
    /**
     * A session being copied for a chat which started over - its next prompt waits for the copy.
     */
    private final Map<String, CompletableFuture<?>> myForks = new ConcurrentHashMap<>();
    private volatile List<AIModel> myModels = FALLBACK_MODELS;
    private volatile @Nullable CompletableFuture<List<AIModel>> myModelsLoading;

    @Inject
    public ClaudeAIProviderType(ProcessHandlerBuilderFactory processHandlerBuilderFactory,
                                ApplicationConcurrency applicationConcurrency,
                                AccountManager accountManager) {
        myProcessHandlerBuilderFactory = processHandlerBuilderFactory;
        myApplicationConcurrency = applicationConcurrency;
        myAccountManager = accountManager;
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
        loadModels();
        return myModels;
    }

    /**
     * An alias the command line does not list - {@code opus} - is the listed model it names, {@code opus[1m]}.
     */
    @Override
    public @Nullable AIModel resolveModel(List<AIModel> models, String id) {
        AIModel model = AIProviderTypeWithChats.super.resolveModel(models, id);
        if (model != null) {
            return model;
        }
        for (AIModel candidate : models) {
            if (candidate.getId().startsWith(id + "[")) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The command line is asked once a run - until it answers, and when it can't, the models are the fallback ones.
     */
    @Override
    public CompletableFuture<List<AIModel>> loadModels() {
        CompletableFuture<List<AIModel>> loading = myModelsLoading;
        if (loading == null) {
            synchronized (this) {
                loading = myModelsLoading;
                if (loading == null) {
                    loading = CompletableFuture.supplyAsync(ClaudeCli::models, myApplicationConcurrency.executor()).thenApply(models -> {
                        if (!models.isEmpty()) {
                            myModels = List.copyOf(models);
                        }
                        return myModels;
                    });
                    myModelsLoading = loading;
                }
            }
        }
        return loading;
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getAccountTypeId() {
        return ClaudeAccountType.ID;
    }

    @RequiredUIAccess
    @Override
    public @Nullable Component createAccountComponent(Map<String, String> options, Disposable uiDisposable) {
        return new ClaudeAccountComponent(options).getComponent();
    }

    /**
     * Configured when the command line is there and the instance has an account which did not fail to sign in -
     * whether the login of the command line itself is still valid shows on the first answer, which carries the reason.
     */
    @Override
    public CompletableFuture<Boolean> isConfigured(AIProvider provider) {
        return AIProviderTypeWithChats.super.isConfigured(provider)
            .thenCombine(CompletableFuture.supplyAsync(() -> ClaudeCli.findExecutable() != null, myApplicationConcurrency.executor()), Boolean::logicalAnd);
    }

    /**
     * An instance from before accounts signed in with an {@value ClaudeAuthMode#OPTION} option - the login of the
     * command line, which becomes the account of it (one for every instance), or a secret of the password safe, which
     * becomes an account of an API key or a token.
     */
    @Override
    public CompletableFuture<@Nullable String> migrateToAccount(AIProvider provider, @Nullable String legacySecret) {
        AccountType<?> type = myAccountManager.findAccountType(ClaudeAccountType.ID);
        if (type == null) {
            return CompletableFuture.completedFuture(null);
        }
        ClaudeAuthMode mode = ClaudeAuthMode.of(provider);
        if (mode == ClaudeAuthMode.CLI) {
            for (Account account : myAccountManager.getAccounts(type, null)) {
                if (ClaudeCliAuthorizeType.ID.equals(account.getAuthorizeTypeId())) {
                    return CompletableFuture.completedFuture(account.getId());
                }
            }
            return myAccountManager.addAccount(type, ClaudeCliAuthorizeConfig.INSTANCE, ClaudeCliCredentials.INSTANCE, null)
                .thenApply(Account::getId);
        }
        if (legacySecret == null || legacySecret.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        AccountAuthorizeConfig config = mode == ClaudeAuthMode.API_KEY ? ClaudeAccountType.API_KEY : ClaudeAccountType.OAUTH_TOKEN;
        AccountAuthorizeType<?> authorizeType = myAccountManager.findAuthorizeType(config);
        if (authorizeType == null) {
            return CompletableFuture.completedFuture(null);
        }
        return addAccount(type, config, authorizeType, legacySecret.strip()).thenApply(Account::getId);
    }

    private <C extends AccountCredentials> CompletableFuture<Account> addAccount(AccountType<?> type,
                                                                                 AccountAuthorizeConfig config,
                                                                                 AccountAuthorizeType<C> authorizeType,
                                                                                 String secret) {
        return myAccountManager.addAccount(type, config, authorizeType.deserialize(secret, config), null);
    }

    @Override
    public CompletableFuture<AIResponse> chat(AIProvider provider, AIRequest request, @Nullable AIStreamListener listener) {
        List<AIContent> prompt = lastUserContent(request);
        String text = lastUserText(request);
        if (prompt == null || text == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Nothing to send - the request has no user text"));
        }

        String chatId = request.getSessionId();
        String sessionId = chatId == null ? null : myAliases.getOrDefault(key(provider, chatId), chatId);
        CompletableFuture<?> fork = chatId == null ? null : myForks.get(key(provider, chatId));
        CompletableFuture<?> ready = fork == null ? CompletableFuture.completedFuture(null) : fork;
        return ready.thenCompose(ignored -> provider.connectAccount()).thenApplyAsync(connection -> {
            ClaudeCredential credential = ClaudeCredential.of(connection);
            try {
                return sessionId == null ? start(provider, request, credential, null) : session(provider, sessionId, request, credential);
            }
            catch (ExecutionException | IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, myApplicationConcurrency.executor()).thenCompose(session -> {
            // a session which never saw the chat - it started over, or the command line lost it - is told it first
            List<AIContent> content = prompt;
            if (chatId != null && session.takeFresh()) {
                String carryover = AIChatCarryover.build(request.getMessages());
                if (carryover != null) {
                    content = new ArrayList<>();
                    content.add(AIContent.text(carryover));
                    content.addAll(prompt);
                }
            }
            CompletableFuture<AIResponse> answer = session.send(content, listener);
            if (sessionId == null) {
                answer.whenComplete((response, error) -> session.close());
            }
            else if (listener != null) {
                answer.thenAccept(response -> askTitle(session, text, response.getText(), listener));
            }
            return answer;
        });
    }

    /**
     * The command line titles a session itself; once the first answer is there its title is asked for - and generated
     * from the conversation when the session has none, as a resumed one - and kept with the session.
     */
    private static void askTitle(ClaudeCliSession session, String prompt, String answer, AIStreamListener listener) {
        if (!session.askTitle()) {
            return;
        }
        String description = prompt + "\n\n" + answer;
        if (description.length() > TITLE_CONTEXT_LENGTH) {
            description = description.substring(description.length() - TITLE_CONTEXT_LENGTH);
        }
        session.generateTitle(description, true).whenComplete((title, error) -> {
            if (title != null) {
                listener.onTitle(title);
            }
        });
    }

    /**
     * The command line can not go back in a session - the session is copied up to the point the chat goes on from, and
     * resumed under a new id. When there is nothing to copy the new session is told what was said instead.
     */
    @Override
    public void startOver(AIProvider provider, String sessionId, List<AIMessage> history) {
        String key = key(provider, sessionId);
        String current = myAliases.getOrDefault(key, sessionId);
        ClaudeCliSession session = mySessions.remove(key(provider, current));
        if (session != null) {
            session.close();
        }
        String next = UUID.randomUUID().toString();
        myAliases.put(key, next);
        int keptPrompts = (int) history.stream().filter(message -> message.role() == AIRole.USER && !message.getText().isBlank()).count();
        CompletableFuture<?> fork = CompletableFuture.runAsync(() -> {
            try {
                ClaudeChats.fork(current, next, keptPrompts);
            }
            catch (IOException e) {
                LOG.warn("Cannot copy the session " + current, e);
            }
        }, myApplicationConcurrency.executor());
        myForks.put(key, fork);
        fork.whenComplete((ignored, error) -> myForks.remove(key, fork));
        for (AIChatSessionListener listener : mySessionListeners.getOrDefault(key, List.of())) {
            listener.sessionChanged(next);
        }
    }

    @Override
    public void addSessionListener(AIProvider provider, String sessionId, AIChatSessionListener listener, Disposable parentDisposable) {
        String key = key(provider, sessionId);
        mySessionListeners.computeIfAbsent(key, id -> new CopyOnWriteArrayList<>()).add(listener);
        Disposer.register(parentDisposable, () -> {
            List<AIChatSessionListener> listeners = mySessionListeners.get(key);
            if (listeners != null) {
                listeners.remove(listener);
            }
        });
    }

    @Override
    public void closeSession(AIProvider provider, String sessionId) {
        ClaudeCliSession session = mySessions.remove(key(provider, sessionId));
        if (session != null) {
            session.close();
        }
    }

    private ClaudeCliSession session(AIProvider provider, String sessionId, AIRequest request, ClaudeCredential credential)
        throws ExecutionException, IOException {
        String key = key(provider, sessionId);
        ClaudeCliSession existing = mySessions.get(key);
        if (existing != null) {
            return existing;
        }
        ClaudeCliSession session = start(provider, request, credential, sessionId);
        ClaudeCliSession raced = mySessions.putIfAbsent(key, session);
        if (raced != null) {
            session.close();
            return raced;
        }
        return session;
    }

    private ClaudeCliSession start(AIProvider provider, AIRequest request, ClaudeCredential credential, @Nullable String sessionId)
        throws ExecutionException, IOException {
        Path executable = ClaudeCli.findExecutable();
        if (executable == null) {
            throw new IOException("The claude command line was not found on the PATH");
        }
        ClaudeModel model = request.getModel() instanceof ClaudeModel claudeModel ? claudeModel : (ClaudeModel) FALLBACK_MODELS.getFirst();
        ClaudeAuthMode authMode = credential.mode();
        String secret = credential.secret();
        if (authMode != ClaudeAuthMode.CLI && (secret == null || secret.isBlank())) {
            throw new IOException("The account signs in with " + authMode.getDisplayName().get() + ", but it has none set");
        }
        Path workingDirectory = request.getWorkingDirectory();
        return ClaudeCliSession.start(
            myProcessHandlerBuilderFactory,
            executable,
            model,
            workingDirectory != null ? workingDirectory : Platform.current().user().homePath(),
            authMode,
            secret,
            ClaudePermissionMode.of(provider),
            sessionId,
            request.getEffort()
        );
    }

    /**
     * The sessions the command line kept for the directory of the project - the ones started in a terminal too.
     */
    @Override
    public CompletableFuture<List<AIChatInfo>> getChats(AIProvider provider, Project project) {
        // the chat tree lists the chats first - the models are asked for then, to be there once a chat opens
        getModels();
        String basePath = project.getBasePath();
        if (basePath == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> ClaudeChats.list(Path.of(basePath)), myApplicationConcurrency.executor());
    }

    @Override
    public CompletableFuture<@Nullable AIChatInfo> getChat(AIProvider provider, Project project, String chatId) {
        return CompletableFuture.supplyAsync(() -> ClaudeChats.find(chatId), myApplicationConcurrency.executor());
    }

    @Override
    public CompletableFuture<List<AIMessage>> loadChat(AIProvider provider, Project project, String chatId) {
        return CompletableFuture.supplyAsync(() -> ClaudeChats.load(chatId), myApplicationConcurrency.executor());
    }

    /**
     * A running session is renamed by the command line itself; one which does not run is named in its file.
     */
    @Override
    public CompletableFuture<?> renameChat(AIProvider provider, Project project, String chatId, String title) {
        ClaudeCliSession session = mySessions.get(key(provider, chatId));
        if (session != null) {
            return session.rename(title);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                ClaudeChats.rename(chatId, title);
            }
            catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, myApplicationConcurrency.executor());
    }

    /**
     * The user deletes the chat with its history - the session file of the command line goes with it.
     */
    @Override
    public CompletableFuture<?> deleteChat(AIProvider provider, Project project, String chatId) {
        closeAll(chatId);
        return CompletableFuture.runAsync(() -> {
            try {
                ClaudeChats.delete(chatId);
            }
            catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, myApplicationConcurrency.executor());
    }

    private void closeAll(String sessionId) {
        mySessions.entrySet().removeIf(entry -> {
            if (entry.getKey().endsWith("/" + sessionId)) {
                entry.getValue().close();
                return true;
            }
            return false;
        });
    }

    private static String key(AIProvider provider, String sessionId) {
        return provider.getName() + "/" + sessionId;
    }

    /**
     * What the user said last - the text, and the images, files and selections it carries.
     */
    private static @Nullable List<AIContent> lastUserContent(AIRequest request) {
        List<AIMessage> messages = request.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AIMessage message = messages.get(i);
            if (message.role() != AIRole.USER) {
                continue;
            }
            List<AIContent> content = new ArrayList<>();
            for (AIContent part : message.content()) {
                if (part instanceof AIContent.Text || part instanceof AIContent.Image
                    || part instanceof AIContent.ResourceLink || part instanceof AIContent.Resource) {
                    content.add(part);
                }
            }
            return content.isEmpty() ? null : content;
        }
        return null;
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

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
import consulo.accountManagement.AccountAuthorizeContext;
import consulo.accountManagement.AccountAuthorizeForm;
import consulo.accountManagement.AccountAuthorizeType;
import consulo.accountManagement.AccountRequestDecorator;
import consulo.annotation.component.ExtensionImpl;
import consulo.application.concurrent.ApplicationConcurrency;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.ui.Button;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * Signs an account in with the login of the claude command line, {@code claude auth login} - the command line keeps
 * the login, so the account keeps nothing, and is signed in as long as the command line is.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
@ExtensionImpl
public class ClaudeCliAuthorizeType implements AccountAuthorizeType<ClaudeCliCredentials> {
    public static final String ID = "claude.cli";

    private final ProcessHandlerBuilderFactory myProcessHandlerBuilderFactory;
    private final ApplicationConcurrency myApplicationConcurrency;

    @Inject
    public ClaudeCliAuthorizeType(ProcessHandlerBuilderFactory processHandlerBuilderFactory, ApplicationConcurrency applicationConcurrency) {
        myProcessHandlerBuilderFactory = processHandlerBuilderFactory;
        myApplicationConcurrency = applicationConcurrency;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getName() {
        return ClaudeLocalize.authModeCli();
    }

    @Override
    public String getCredentialSourceId() {
        return NONE_SOURCE;
    }

    @RequiredUIAccess
    @Override
    public AccountAuthorizeForm<ClaudeCliCredentials> createForm(AccountAuthorizeContext context) {
        return new Form();
    }

    @Override
    public String serialize(ClaudeCliCredentials credentials, AccountAuthorizeConfig config) {
        return "";
    }

    @Override
    public ClaudeCliCredentials deserialize(String value, AccountAuthorizeConfig config) {
        return ClaudeCliCredentials.INSTANCE;
    }

    @Override
    public void decorate(ClaudeCliCredentials credentials, AccountAuthorizeConfig config, AccountRequestDecorator decorator) {
    }

    /**
     * What the command line is signed in as, and the buttons to sign it in - the account is added once it is.
     */
    private final class Form implements AccountAuthorizeForm<ClaudeCliCredentials> {
        private final UIAccess myUIAccess = UIAccess.current();
        private final VerticalLayout myRoot = VerticalLayout.create();
        private final Label myStatus = Label.create(ClaudeLocalize.statusChecking());
        private final Button myLogin;
        private final Button myConsoleLogin;

        @RequiredUIAccess
        Form() {
            myLogin = Button.create(ClaudeLocalize.buttonLogIn(), event -> login(false));
            myLogin.setToolTipText(ClaudeLocalize.buttonLogInTooltip());
            myConsoleLogin = Button.create(ClaudeLocalize.buttonConsoleLogIn(), event -> login(true));
            myConsoleLogin.setToolTipText(ClaudeLocalize.buttonConsoleLogInTooltip());
            myRoot.add(DockLayout.create().left(Label.create(ClaudeLocalize.labelCommandLine())).right(myStatus));
            myRoot.add(DockLayout.create().right(HorizontalLayout.create().add(myLogin).add(myConsoleLogin)));
            refresh();
        }

        @Override
        public Component getComponent() {
            return myRoot;
        }

        @RequiredUIAccess
        @Override
        public CompletableFuture<ClaudeCliCredentials> authorize() {
            return CompletableFuture.supplyAsync(ClaudeCli::status, myApplicationConcurrency.executor()).thenApply(status -> {
                if (!status.loggedIn()) {
                    throw new IllegalStateException(status.error() != null ? status.error() : ClaudeLocalize.errorNotSignedIn().get());
                }
                return ClaudeCliCredentials.INSTANCE;
            });
        }

        @RequiredUIAccess
        private void refresh() {
            setBusy(true);
            CompletableFuture.supplyAsync(ClaudeCli::status, myApplicationConcurrency.executor()).whenCompleteAsync((status, error) -> {
                setBusy(false);
                if (status == null) {
                    myStatus.setText(ClaudeLocalize.statusCannotRead(error == null ? "" : String.valueOf(error.getMessage())));
                }
                else if (status.error() != null) {
                    myStatus.setText(LocalizeValue.of(status.error()));
                }
                else if (status.loggedIn()) {
                    String account = status.account();
                    myStatus.setText(account == null
                        ? ClaudeLocalize.statusSignedIn(status.authMethod())
                        : ClaudeLocalize.statusSignedInAs(account, status.authMethod()));
                }
                else {
                    myStatus.setText(ClaudeLocalize.statusNotSignedIn());
                }
            }, myUIAccess);
        }

        @RequiredUIAccess
        private void login(boolean console) {
            setBusy(true);
            myStatus.setText(ClaudeLocalize.statusWaitingBrowser());
            ClaudeCliLogin.start(myProcessHandlerBuilderFactory, myUIAccess, myRoot, console).whenCompleteAsync((exitCode, error) -> {
                setBusy(false);
                if (error != null || exitCode == null || exitCode != 0) {
                    myStatus.setText(error == null
                        ? ClaudeLocalize.statusSignInFailed()
                        : ClaudeLocalize.statusSignInFailedReason(String.valueOf(error.getMessage())));
                    return;
                }
                refresh();
            }, myUIAccess);
        }

        @RequiredUIAccess
        private void setBusy(boolean busy) {
            myLogin.setEnabled(!busy);
            myConsoleLogin.setEnabled(!busy);
        }
    }
}

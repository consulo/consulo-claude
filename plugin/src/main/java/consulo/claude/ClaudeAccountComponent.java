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

import consulo.application.concurrent.ApplicationConcurrency;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.ui.Button;
import consulo.ui.ComboBox;
import consulo.ui.Component;
import consulo.ui.Label;
import consulo.ui.UIAccess;
import consulo.ui.annotation.RequiredUIAccess;
import consulo.ui.layout.DockLayout;
import consulo.ui.layout.HorizontalLayout;
import consulo.ui.layout.VerticalLayout;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * How a Claude account signs in - the login of the command line, an API key or an OAuth token - and the login of the
 * command line itself: what it is signed in as, and the buttons to sign in or out.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
final class ClaudeAccountComponent {
    private final ProcessHandlerBuilderFactory myProcessHandlerBuilderFactory;
    private final ApplicationConcurrency myApplicationConcurrency;
    private final UIAccess myUIAccess;

    private final Label myStatus = Label.create(ClaudeLocalize.statusChecking());
    private final Button myLogin;
    private final Button myConsoleLogin;
    private final Button myLogout;
    private final VerticalLayout myRoot = VerticalLayout.create();
    private final VerticalLayout myCliLogin = VerticalLayout.create();

    @RequiredUIAccess
    ClaudeAccountComponent(ProcessHandlerBuilderFactory processHandlerBuilderFactory,
                           ApplicationConcurrency applicationConcurrency,
                           Map<String, String> options) {
        myProcessHandlerBuilderFactory = processHandlerBuilderFactory;
        myApplicationConcurrency = applicationConcurrency;
        myUIAccess = UIAccess.current();

        ComboBox<ClaudeAuthMode> modeBox = ComboBox.create(ClaudeAuthMode.values());
        modeBox.setTextRenderer(mode -> mode == null ? LocalizeValue.empty() : mode.getDisplayName());
        modeBox.setValue(ClaudeAuthMode.of(options));
        modeBox.addValueListener(event -> {
            ClaudeAuthMode mode = event.getValue();
            if (mode != null) {
                mode.store(options);
            }
            myCliLogin.setVisible(mode == ClaudeAuthMode.CLI);
        });
        myRoot.add(DockLayout.create().left(Label.create(ClaudeLocalize.labelSignInWith())).right(modeBox));

        ComboBox<ClaudePermissionMode> permissionBox = ComboBox.create(ClaudePermissionMode.values());
        permissionBox.setTextRenderer(mode -> mode == null ? LocalizeValue.empty() : mode.getDisplayName());
        permissionBox.setValue(ClaudePermissionMode.of(options));
        permissionBox.addValueListener(event -> {
            ClaudePermissionMode mode = event.getValue();
            if (mode != null) {
                mode.store(options);
            }
        });
        myRoot.add(DockLayout.create().left(Label.create(ClaudeLocalize.labelToolPermissions())).right(permissionBox));

        myLogin = Button.create(ClaudeLocalize.buttonLogIn(), event -> login(false));
        myLogin.setToolTipText(ClaudeLocalize.buttonLogInTooltip());
        myConsoleLogin = Button.create(ClaudeLocalize.buttonConsoleLogIn(), event -> login(true));
        myConsoleLogin.setToolTipText(ClaudeLocalize.buttonConsoleLogInTooltip());
        myLogout = Button.create(ClaudeLocalize.buttonLogOut(), event -> logout());

        myCliLogin.add(DockLayout.create().left(Label.create(ClaudeLocalize.labelCommandLine())).right(myStatus));
        myCliLogin.add(DockLayout.create().right(HorizontalLayout.create().add(myLogin).add(myConsoleLogin).add(myLogout)));
        myCliLogin.setVisible(ClaudeAuthMode.of(options) == ClaudeAuthMode.CLI);
        myRoot.add(myCliLogin);

        refresh();
    }

    Component getComponent() {
        return myRoot;
    }

    @RequiredUIAccess
    private void refresh() {
        setBusy(true);
        CompletableFuture.supplyAsync(ClaudeCli::status, myApplicationConcurrency.executor())
            .whenCompleteAsync((status, error) -> {
                setBusy(false);
                if (status == null) {
                    myStatus.setText(ClaudeLocalize.statusCannotRead(error == null ? "" : String.valueOf(error.getMessage())));
                    return;
                }
                if (status.error() != null) {
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
                myLogout.setEnabled(status.loggedIn());
            }, myUIAccess);
    }

    @RequiredUIAccess
    private void login(boolean console) {
        setBusy(true);
        myStatus.setText(ClaudeLocalize.statusWaitingBrowser());
        ClaudeCliLogin.start(myProcessHandlerBuilderFactory, myUIAccess, myRoot, console)
            .whenCompleteAsync((exitCode, error) -> {
                setBusy(false);
                if (error != null || exitCode == null || exitCode != 0) {
                    myStatus.setText((error == null ? ClaudeLocalize.statusSignInFailed() : ClaudeLocalize.statusSignInFailedReason(String.valueOf(error.getMessage()))));
                    return;
                }
                refresh();
            }, myUIAccess);
    }

    @RequiredUIAccess
    private void logout() {
        setBusy(true);
        CompletableFuture.runAsync(() -> {
            try {
                ClaudeCli.logout();
            }
            catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }, myApplicationConcurrency.executor()).whenCompleteAsync((ignored, error) -> refresh(), myUIAccess);
    }

    @RequiredUIAccess
    private void setBusy(boolean busy) {
        myLogin.setEnabled(!busy);
        myConsoleLogin.setEnabled(!busy);
        myLogout.setEnabled(!busy);
    }
}

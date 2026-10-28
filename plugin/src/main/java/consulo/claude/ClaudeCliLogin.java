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

import consulo.claude.localize.ClaudeLocalize;
import consulo.logging.Logger;
import consulo.platform.Platform;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.process.ProcessOutputTypes;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.ui.Component;
import consulo.ui.InputBoxBuilder;
import consulo.ui.UIAccess;
import consulo.util.dataholder.Key;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Signs the command line in the way {@code claude auth login} does it without a terminal: it prints a url, waits for
 * the code the page shows after signing in, and stores the login itself. The url opens in the browser of whoever
 * clicked - on the web frontend that is the browser page, not the machine the command line runs on.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
final class ClaudeCliLogin {
    private static final Logger LOG = Logger.getInstance(ClaudeCliLogin.class);

    private static final Pattern URL = Pattern.compile("https://\\S+");
    private static final String CODE_PROMPT = "Paste code";

    private final ProcessHandler myHandler;
    private final UIAccess myUIAccess;
    private final Component myParent;
    private final StringBuilder myOutput = new StringBuilder();
    private final CompletableFuture<Integer> myResult = new CompletableFuture<>();

    private boolean myUrlOpened;
    private boolean myCodeAsked;

    private ClaudeCliLogin(ProcessHandler handler, UIAccess uiAccess, Component parent) {
        myHandler = handler;
        myUIAccess = uiAccess;
        myParent = parent;
    }

    /**
     * @param console sign in to the Anthropic Console (API billing) rather than with a Claude subscription
     * @return completes with the exit code of the command line once the login ended
     */
    static CompletableFuture<Integer> start(ProcessHandlerBuilderFactory factory, UIAccess uiAccess, Component parent, boolean console) {
        Path executable = ClaudeCli.findExecutable();
        if (executable == null) {
            return CompletableFuture.failedFuture(new IOException("The claude command line was not found on the PATH"));
        }

        GeneralCommandLine commandLine = ClaudeCli.commandLine(executable, "auth", "login", console ? "--console" : "--claudeai")
            .withEnvironment("BROWSER", "echo");
        try {
            ProcessHandler handler = factory.newBuilder(commandLine).killable().build();
            ClaudeCliLogin login = new ClaudeCliLogin(handler, uiAccess, parent);
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void onTextAvailable(ProcessEvent event, Key outputType) {
                    if (outputType != ProcessOutputTypes.SYSTEM) {
                        login.onOutput(event.getText());
                    }
                }

                @Override
                public void processTerminated(ProcessEvent event) {
                    login.myResult.complete(event.getExitCode());
                }
            });
            handler.startNotify();
            return login.myResult;
        }
        catch (ExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private synchronized void onOutput(String text) {
        myOutput.append(text);

        if (!myUrlOpened) {
            Matcher matcher = URL.matcher(myOutput);
            if (matcher.find()) {
                myUrlOpened = true;
                String url = matcher.group();
                myUIAccess.give(() -> Platform.current().openInBrowser(url));
            }
        }

        if (!myCodeAsked && myOutput.indexOf(CODE_PROMPT) >= 0) {
            myCodeAsked = true;
            myUIAccess.give(this::askCode);
        }
    }

    private void askCode() {
        InputBoxBuilder.text()
            .title(ClaudeLocalize.dialogSignInTitle())
            .text(ClaudeLocalize.dialogSignInPrompt())
            .showAsync(myParent)
            .whenComplete((code, error) -> {
                if (error != null || code == null || code.isBlank()) {
                    myHandler.destroyProcess();
                    return;
                }
                try {
                    OutputStream input = myHandler.getProcessInput();
                    if (input == null) {
                        throw new IOException("claude has no input stream");
                    }
                    input.write((code.strip() + "\n").getBytes(StandardCharsets.UTF_8));
                    input.flush();
                }
                catch (IOException e) {
                    LOG.warn("Cannot pass the code to claude auth login", e);
                    myHandler.destroyProcess();
                }
            });
    }
}

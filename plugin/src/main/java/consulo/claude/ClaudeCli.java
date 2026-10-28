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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import consulo.platform.Platform;
import consulo.process.ExecutionException;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.util.CapturingProcessUtil;
import consulo.process.util.ProcessOutput;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The claude command line as a tool: where it is and what its login looks like.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
final class ClaudeCli {
    static final String EXECUTABLE = "claude";

    private static final int TIMEOUT_MILLIS = 20_000;

    static @Nullable Path findExecutable() {
        return Platform.current().findExecutableInPath(EXECUTABLE);
    }

    static GeneralCommandLine commandLine(Path executable, String... parameters) {
        return new GeneralCommandLine()
            .withExecutablePath(executable)
            .withParameters(parameters)
            .withWorkDirectory(Platform.current().user().homePath().toString())
            .withCharset(StandardCharsets.UTF_8);
    }

    /**
     * Blocks while the command line answers - call it off the ui thread.
     */
    static ClaudeAuthStatus status() {
        Path executable = findExecutable();
        if (executable == null) {
            return ClaudeAuthStatus.failed("The claude command line was not found on the PATH");
        }
        try {
            ProcessOutput output = CapturingProcessUtil.execAndGetOutput(commandLine(executable, "auth", "status", "--json"), TIMEOUT_MILLIS);
            if (output.isTimeout()) {
                return ClaudeAuthStatus.failed("claude auth status did not answer");
            }
            JsonObject json = JsonParser.parseString(output.getStdout()).getAsJsonObject();
            boolean loggedIn = json.has("loggedIn") && json.get("loggedIn").getAsBoolean();
            return new ClaudeAuthStatus(loggedIn, string(json, "authMethod", "none"), account(json), null);
        }
        catch (ExecutionException | RuntimeException e) {
            return ClaudeAuthStatus.failed(String.valueOf(e.getMessage()));
        }
    }

    /**
     * Blocks while the command line logs out - call it off the ui thread.
     */
    static void logout() throws ExecutionException {
        Path executable = findExecutable();
        if (executable == null) {
            throw new ExecutionException("The claude command line was not found on the PATH");
        }
        CapturingProcessUtil.execAndGetOutput(commandLine(executable, "auth", "logout"), TIMEOUT_MILLIS);
    }

    private static @Nullable String account(JsonObject json) {
        for (String name : new String[]{"email", "emailAddress", "accountEmail", "organizationName", "orgName"}) {
            String value = string(json, name, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static String string(JsonObject json, String name, String defaultValue) {
        JsonElement element = json.get(name);
        return element == null || !element.isJsonPrimitive() ? defaultValue : element.getAsString();
    }

    private ClaudeCli() {
    }
}

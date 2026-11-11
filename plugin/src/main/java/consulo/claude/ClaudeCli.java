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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * The models the account can use, as the command line itself lists them - asked with the {@code initialize} control
     * request of a process which is given no prompt, so it costs nothing. Empty when it does not answer. Blocks while
     * it answers - call it off the ui thread.
     */
    static List<ClaudeModel> models() {
        Path executable = findExecutable();
        if (executable == null) {
            return List.of();
        }
        GeneralCommandLine commandLine = commandLine(executable,
            "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose");
        Process process;
        try {
            process = commandLine.createProcess();
        }
        catch (ExecutionException e) {
            return List.of();
        }
        try {
            JsonObject request = new JsonObject();
            request.addProperty("subtype", "initialize");
            JsonObject line = new JsonObject();
            line.addProperty("type", "control_request");
            line.addProperty("request_id", "consulo-models");
            line.add("request", request);
            OutputStream input = process.getOutputStream();
            input.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();

            CompletableFuture<List<ClaudeModel>> models = CompletableFuture.supplyAsync(() -> readModels(process));
            return models.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (IOException | InterruptedException | java.util.concurrent.ExecutionException | TimeoutException e) {
            return List.of();
        }
        finally {
            process.destroy();
        }
    }

    private static List<ClaudeModel> readModels(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String text;
            while ((text = reader.readLine()) != null) {
                JsonObject event;
                try {
                    event = JsonParser.parseString(text).getAsJsonObject();
                }
                catch (RuntimeException e) {
                    continue;
                }
                if (!"control_response".equals(string(event, "type", ""))) {
                    continue;
                }
                JsonObject response = event.getAsJsonObject("response");
                JsonElement body = response == null ? null : response.get("response");
                JsonElement list = body != null && body.isJsonObject() ? body.getAsJsonObject().get("models") : null;
                List<ClaudeModel> models = new ArrayList<>();
                if (list != null && list.isJsonArray()) {
                    for (JsonElement element : list.getAsJsonArray()) {
                        if (element.isJsonObject()) {
                            JsonObject model = element.getAsJsonObject();
                            String id = string(model, "value", "");
                            if (!id.isEmpty()) {
                                String description = string(model, "description", "");
                                models.add(new ClaudeModel(
                                    id,
                                    modelName(id, string(model, "displayName", id), description),
                                    modelDescription(description),
                                    -1,
                                    ClaudeModel.effortLevels(strings(model, "supportedEffortLevels"))
                                ));
                            }
                        }
                    }
                }
                return models;
            }
        }
        catch (IOException ignored) {
        }
        return List.of();
    }

    /**
     * The name of the model as its description gives it - "Fable 5.1", "Opus 5.5 with 1M context" - where the display
     * name of the command line says only "Fable" or "Default (recommended)".
     */
    private static String modelName(String id, String displayName, String description) {
        String model = description.split(" \u00b7 ")[0].strip();
        if (model.isEmpty() || model.startsWith("Newer version")) {
            return displayName;
        }
        return ClaudeModel.DEFAULT_ID.equals(id) ? "Default (" + model + ")" : model;
    }

    /**
     * What the description says the model is good for - "Best for everyday, complex tasks" - without the name it starts
     * with.
     */
    private static String modelDescription(String description) {
        int separator = description.indexOf(" \u00b7 ");
        return separator < 0 ? "" : description.substring(separator + 3).strip();
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

    private static List<String> strings(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                result.add(item.getAsString());
            }
        }
        return result;
    }

    private ClaudeCli() {
    }
}

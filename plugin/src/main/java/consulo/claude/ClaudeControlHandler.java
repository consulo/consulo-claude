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
import consulo.ai.AIQuestion;
import consulo.ai.AIQuestionOption;
import consulo.ai.AIStreamListener;
import consulo.ai.AIToolPermissionDecision;
import consulo.ai.AIToolPermissionRequest;
import consulo.logging.Logger;
import consulo.util.lang.function.ThrowableConsumer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Answers the requests the command line sends to its host over stdio when started with
 * {@code --permission-prompt-tool stdio}: whether a tool may run ({@code can_use_tool}), including the questions of
 * its {@code AskUserQuestion} tool, whose answers go back as the tool's input.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
final class ClaudeControlHandler {
    private static final Logger LOG = Logger.getInstance(ClaudeControlHandler.class);

    private static final String ASK_USER_QUESTION = "AskUserQuestion";

    private static final AIStreamListener NOBODY = delta -> {
    };

    private final ThrowableConsumer<JsonObject, IOException> myWriter;
    private final Supplier<@Nullable AIStreamListener> myListener;
    private final Map<String, CompletableFuture<?>> myPending = new ConcurrentHashMap<>();

    ClaudeControlHandler(ThrowableConsumer<JsonObject, IOException> writer, Supplier<@Nullable AIStreamListener> listener) {
        myWriter = writer;
        myListener = listener;
    }

    void onRequest(JsonObject event) {
        String requestId = string(event, "request_id");
        JsonElement requestElement = event.get("request");
        JsonObject request = requestElement != null && requestElement.isJsonObject() ? requestElement.getAsJsonObject() : new JsonObject();
        String subtype = string(request, "subtype");
        if (!"can_use_tool".equals(subtype)) {
            write(error(requestId, "Not supported by this host: " + subtype));
            return;
        }

        String toolName = string(request, "tool_name");
        String toolUseId = string(request, "tool_use_id");
        JsonElement inputElement = request.get("input");
        JsonObject input = inputElement != null && inputElement.isJsonObject() ? inputElement.getAsJsonObject() : new JsonObject();

        AIStreamListener listener = myListener.get();
        if (listener == null) {
            listener = NOBODY;
        }

        if (ASK_USER_QUESTION.equals(toolName)) {
            CompletableFuture<Map<String, String>> answers = listener.onQuestion(questions(input));
            track(requestId, answers, (result, error) -> {
                if (result == null) {
                    return deny("The user declined to answer", toolUseId);
                }
                JsonObject updated = input.deepCopy();
                JsonObject answerObject = new JsonObject();
                result.forEach(answerObject::addProperty);
                updated.add("answers", answerObject);
                return allow(updated, toolUseId);
            });
            return;
        }

        String displayName = string(request, "display_name");
        String description = string(request, "description");
        AIToolPermissionRequest permissionRequest = new AIToolPermissionRequest(
            toolUseId,
            toolName,
            displayName.isEmpty() ? toolName : displayName,
            description.isEmpty() ? summary(toolName, input) : description,
            input.toString()
        );
        CompletableFuture<AIToolPermissionDecision> decision = listener.onToolPermission(permissionRequest);
        track(requestId, decision, (result, error) -> {
            if (result == null) {
                return deny(error == null ? "Denied" : String.valueOf(error.getMessage()), toolUseId);
            }
            if (!result.allowed()) {
                return deny(result.message().isEmpty() ? "The user denied this tool call" : result.message(), toolUseId);
            }
            return allow(input, toolUseId);
        });
    }

    void onCancel(JsonObject event) {
        CompletableFuture<?> pending = myPending.remove(string(event, "request_id"));
        if (pending != null) {
            pending.cancel(false);
        }
    }

    /**
     * Withdraws every unanswered request - the turn or the process is over, nobody waits for the answers.
     */
    void cancelAll() {
        List<CompletableFuture<?>> pending = new ArrayList<>(myPending.values());
        myPending.clear();
        for (CompletableFuture<?> future : pending) {
            future.cancel(false);
        }
    }

    private <T> void track(String requestId, CompletableFuture<T> future, BiFunction<@Nullable T, @Nullable Throwable, JsonObject> responder) {
        myPending.put(requestId, future);
        future.whenComplete((result, error) -> {
            // withdrawn by the command line or by the end of the turn - nobody reads an answer
            if (myPending.remove(requestId) == null) {
                return;
            }
            write(success(requestId, responder.apply(result, error)));
        });
    }

    private void write(JsonObject line) {
        try {
            myWriter.consume(line);
        }
        catch (IOException e) {
            LOG.warn("Cannot answer the claude command line", e);
        }
    }

    private static List<AIQuestion> questions(JsonObject input) {
        List<AIQuestion> questions = new ArrayList<>();
        JsonElement questionsElement = input.get("questions");
        if (questionsElement == null || !questionsElement.isJsonArray()) {
            return questions;
        }
        for (JsonElement element : questionsElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject question = element.getAsJsonObject();
            List<AIQuestionOption> options = new ArrayList<>();
            JsonElement optionsElement = question.get("options");
            if (optionsElement != null && optionsElement.isJsonArray()) {
                for (JsonElement option : optionsElement.getAsJsonArray()) {
                    if (option.isJsonObject()) {
                        options.add(new AIQuestionOption(string(option.getAsJsonObject(), "label"), string(option.getAsJsonObject(), "description")));
                    }
                }
            }
            JsonElement multiSelect = question.get("multiSelect");
            questions.add(new AIQuestion(
                string(question, "question"),
                string(question, "header"),
                options,
                multiSelect != null && multiSelect.isJsonPrimitive() && multiSelect.getAsBoolean()
            ));
        }
        return questions;
    }

    /**
     * The part of the input that tells the user what the call does, for the tools whose input shows it plainly.
     */
    private static @Nullable String summary(String toolName, JsonObject input) {
        String value = switch (toolName) {
            case "Bash" -> string(input, "command");
            case "Edit", "MultiEdit", "Write", "Read", "NotebookEdit" -> string(input, "file_path");
            case "WebFetch" -> string(input, "url");
            case "WebSearch" -> string(input, "query");
            case "Glob", "Grep" -> string(input, "pattern");
            default -> "";
        };
        return value.isEmpty() ? null : value;
    }

    private static JsonObject allow(JsonObject updatedInput, String toolUseId) {
        JsonObject response = new JsonObject();
        response.addProperty("behavior", "allow");
        response.add("updatedInput", updatedInput);
        if (!toolUseId.isEmpty()) {
            response.addProperty("toolUseID", toolUseId);
        }
        return response;
    }

    private static JsonObject deny(String message, String toolUseId) {
        JsonObject response = new JsonObject();
        response.addProperty("behavior", "deny");
        response.addProperty("message", message);
        if (!toolUseId.isEmpty()) {
            response.addProperty("toolUseID", toolUseId);
        }
        return response;
    }

    private static JsonObject success(String requestId, JsonObject response) {
        JsonObject body = new JsonObject();
        body.addProperty("subtype", "success");
        body.addProperty("request_id", requestId);
        body.add("response", response);
        return line(body);
    }

    private static JsonObject error(String requestId, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("subtype", "error");
        body.addProperty("request_id", requestId);
        body.addProperty("error", message);
        return line(body);
    }

    private static JsonObject line(JsonObject body) {
        JsonObject line = new JsonObject();
        line.addProperty("type", "control_response");
        line.add("response", body);
        return line;
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }
}

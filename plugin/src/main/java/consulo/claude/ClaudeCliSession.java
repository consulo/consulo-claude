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
import consulo.ai.AIMessage;
import consulo.ai.AIResponse;
import consulo.ai.AIStopReason;
import consulo.ai.AIStreamListener;
import consulo.ai.AIUsage;
import consulo.logging.Logger;
import consulo.process.ExecutionException;
import consulo.process.ProcessHandler;
import consulo.process.ProcessHandlerBuilderFactory;
import consulo.process.ProcessOutputTypes;
import consulo.process.cmd.GeneralCommandLine;
import consulo.process.event.ProcessEvent;
import consulo.process.event.ProcessListener;
import consulo.util.dataholder.Key;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One conversation with the claude command line: a single long-lived {@code claude -p} process speaking stream-json,
 * which keeps the history on its side. A turn is written as one json line, the answer streams back as events and
 * ends with a {@code result} event.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
class ClaudeCliSession {
    private static final Logger LOG = Logger.getInstance(ClaudeCliSession.class);

    private static final int STDERR_TAIL = 20;

    private record Turn(CompletableFuture<AIResponse> result, @Nullable AIStreamListener listener, StringBuilder text) {
    }

    private final ProcessHandler myHandler;
    private final StringBuilder myLine = new StringBuilder();
    private final Deque<String> myStderr = new ArrayDeque<>();
    private final ClaudeControlHandler myControlHandler = new ClaudeControlHandler(this::write, this::currentListener);

    private @Nullable Turn myTurn;
    private @Nullable String myCliSessionId;
    private final Map<String, CompletableFuture<JsonObject>> myControlRequests = new ConcurrentHashMap<>();
    private boolean myTitleAsked;

    private ClaudeCliSession(ProcessHandler handler) {
        myHandler = handler;
    }

    /**
     * @param secret    the API key or the OAuth token of the account, passed the way {@code authMode} says
     * @param sessionId the id of the conversation - the command line resumes it when it kept it, and starts it under
     *                  this id otherwise, so the chat of the IDE and the session of the command line are one; null for
     *                  a one-off request
     */
    static ClaudeCliSession start(ProcessHandlerBuilderFactory factory,
                                  Path executable,
                                  ClaudeModel model,
                                  Path workDirectory,
                                  ClaudeAuthMode authMode,
                                  @Nullable String secret,
                                  ClaudePermissionMode permissionMode,
                                  @Nullable String sessionId,
                                  @Nullable String effort) throws ExecutionException {
        List<String> parameters = new ArrayList<>(List.of(
            "-p",
            "--input-format", "stream-json",
            "--output-format", "stream-json",
            "--include-partial-messages",
            "--verbose",
            "--permission-prompt-tool", "stdio"
        ));
        parameters.addAll(permissionMode.getParameters());
        if (!ClaudeModel.DEFAULT_ID.equals(model.id())) {
            parameters.add("--model");
            parameters.add(model.id());
        }
        if (effort != null && !effort.isBlank()) {
            parameters.add("--effort");
            parameters.add(effort);
        }
        if (sessionId != null) {
            parameters.add(ClaudeChats.exists(workDirectory, sessionId) ? "--resume" : "--session-id");
            parameters.add(sessionId);
        }

        GeneralCommandLine commandLine = new GeneralCommandLine()
            .withExecutablePath(executable)
            .withParameters(parameters)
            .withWorkingDirectory(workDirectory)
            .withCharset(StandardCharsets.UTF_8);
        String variable = authMode.getEnvironmentVariable();
        if (variable != null && secret != null && !secret.isBlank()) {
            commandLine.withEnvironment(variable, secret.strip());
        }

        ProcessHandler handler = factory.newBuilder(commandLine).killable().build();
        ClaudeCliSession session = new ClaudeCliSession(handler);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(ProcessEvent event, Key outputType) {
                if (outputType == ProcessOutputTypes.STDOUT) {
                    session.onStdout(event.getText());
                }
                else if (outputType == ProcessOutputTypes.STDERR) {
                    session.onStderr(event.getText());
                }
            }

            @Override
            public void processTerminated(ProcessEvent event) {
                session.onTerminated(event.getExitCode());
            }
        });
        handler.startNotify();
        return session;
    }

    synchronized CompletableFuture<AIResponse> send(String text, @Nullable AIStreamListener listener) {
        if (myTurn != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The previous answer has not finished yet"));
        }
        if (myHandler.isProcessTerminated()) {
            return CompletableFuture.failedFuture(new IOException("claude exited: " + stderrTail()));
        }

        Turn turn = new Turn(new CompletableFuture<>(), listener, new StringBuilder());
        myTurn = turn;

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", text);
        JsonObject line = new JsonObject();
        line.addProperty("type", "user");
        line.add("message", message);

        try {
            write(line);
        }
        catch (IOException e) {
            myTurn = null;
            return CompletableFuture.failedFuture(e);
        }
        return turn.result();
    }

    private void write(JsonObject line) throws IOException {
        OutputStream input = myHandler.getProcessInput();
        if (input == null) {
            throw new IOException("claude has no input stream");
        }
        synchronized (input) {
            input.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
        }
    }

    private synchronized @Nullable AIStreamListener currentListener() {
        Turn turn = myTurn;
        return turn == null ? null : turn.listener();
    }

    void close() {
        myControlHandler.cancelAll();
        if (!myHandler.isProcessTerminated()) {
            myHandler.destroyProcess();
        }
    }

    @Nullable String getCliSessionId() {
        return myCliSessionId;
    }

    private void onStdout(String text) {
        List<String> lines = new ArrayList<>();
        synchronized (myLine) {
            myLine.append(text);
            int newLine;
            while ((newLine = myLine.indexOf("\n")) >= 0) {
                lines.add(myLine.substring(0, newLine));
                myLine.delete(0, newLine + 1);
            }
        }
        for (String line : lines) {
            if (!line.isBlank()) {
                onEvent(line);
            }
        }
    }

    private synchronized void onStderr(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                myStderr.addLast(line);
                if (myStderr.size() > STDERR_TAIL) {
                    myStderr.removeFirst();
                }
            }
        }
    }

    private void onEvent(String line) {
        JsonObject event;
        try {
            event = JsonParser.parseString(line).getAsJsonObject();
        }
        catch (RuntimeException e) {
            LOG.warn("Not a stream-json line from claude: " + line);
            return;
        }

        switch (string(event, "type")) {
            case "system" -> {
                String sessionId = string(event, "session_id");
                if (!sessionId.isEmpty()) {
                    myCliSessionId = sessionId;
                }
            }
            case "stream_event" -> onStreamEvent(event.getAsJsonObject("event"));
            case "result" -> onResult(event);
            case "control_request" -> myControlHandler.onRequest(event);
            case "control_cancel_request" -> myControlHandler.onCancel(event);
            case "control_response" -> onControlResponse(event.getAsJsonObject("response"));
            default -> {
            }
        }
    }

    /**
     * The title the command line keeps for the session, generated from {@code description} when it has none yet - it
     * titles a new session itself a few seconds after the first prompt, and answers once that title is there. With
     * {@code persist} the title is stored with the session as an {@code ai-title} record. Null when it has no title to
     * give.
     */
    CompletableFuture<@Nullable String> generateTitle(String description, boolean persist) {
        JsonObject request = new JsonObject();
        request.addProperty("subtype", "generate_session_title");
        request.addProperty("description", description);
        request.addProperty("persist", persist);
        return controlRequest(request).thenApply(response -> {
            JsonElement title = response.get("title");
            return title == null || !title.isJsonPrimitive() || title.getAsString().isBlank() ? null : title.getAsString();
        });
    }

    /**
     * True the first time only - the title of a session is asked for once a process.
     */
    synchronized boolean askTitle() {
        boolean first = !myTitleAsked;
        myTitleAsked = true;
        return first;
    }

    /**
     * Names the session the way the command line does for {@code /rename}.
     */
    CompletableFuture<?> rename(String title) {
        JsonObject request = new JsonObject();
        request.addProperty("subtype", "rename_session");
        request.addProperty("title", title);
        request.addProperty("source", "host");
        String sessionId = myCliSessionId;
        if (sessionId != null) {
            request.addProperty("session_id", sessionId);
        }
        return controlRequest(request);
    }

    /**
     * A control request of ours, answered by a {@code control_response} with the same id.
     */
    private CompletableFuture<JsonObject> controlRequest(JsonObject request) {
        String requestId = "consulo-" + UUID.randomUUID();
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        myControlRequests.put(requestId, response);

        JsonObject line = new JsonObject();
        line.addProperty("type", "control_request");
        line.addProperty("request_id", requestId);
        line.add("request", request);
        try {
            write(line);
        }
        catch (IOException e) {
            myControlRequests.remove(requestId);
            response.completeExceptionally(e);
        }
        return response;
    }

    private void onControlResponse(@Nullable JsonObject response) {
        if (response == null) {
            return;
        }
        CompletableFuture<JsonObject> pending = myControlRequests.remove(string(response, "request_id"));
        if (pending == null) {
            return;
        }
        if ("error".equals(string(response, "subtype"))) {
            String error = string(response, "error");
            pending.completeExceptionally(new IOException(error.isEmpty() ? "claude refused the request" : error));
            return;
        }
        JsonElement body = response.get("response");
        pending.complete(body != null && body.isJsonObject() ? body.getAsJsonObject() : new JsonObject());
    }

    private void onStreamEvent(@Nullable JsonObject event) {
        Turn turn = myTurn;
        if (turn == null || event == null || !"content_block_delta".equals(string(event, "type"))) {
            return;
        }
        JsonObject delta = event.getAsJsonObject("delta");
        if (delta == null) {
            return;
        }
        AIStreamListener listener = turn.listener();
        switch (string(delta, "type")) {
            case "text_delta" -> {
                String text = string(delta, "text");
                turn.text().append(text);
                if (listener != null) {
                    listener.onTextDelta(text);
                }
            }
            case "thinking_delta" -> {
                if (listener != null) {
                    listener.onThinkingDelta(string(delta, "thinking"));
                }
            }
            default -> {
            }
        }
    }

    private void onResult(JsonObject event) {
        myControlHandler.cancelAll();
        Turn turn;
        synchronized (this) {
            turn = myTurn;
            myTurn = null;
        }
        if (turn == null) {
            return;
        }

        String result = string(event, "result");
        JsonElement error = event.get("is_error");
        if (error != null && error.getAsBoolean()) {
            turn.result().completeExceptionally(new IOException(result.isEmpty() ? "claude reported an error" : result));
            return;
        }

        String text = turn.text().isEmpty() ? result : turn.text().toString();
        turn.result().complete(new AIResponse(AIMessage.assistant(text), AIStopReason.END_TURN, usage(event.getAsJsonObject("usage"))));
    }

    private void onTerminated(int exitCode) {
        myControlHandler.cancelAll();
        IOException exited = new IOException("claude exited with " + exitCode);
        for (CompletableFuture<JsonObject> pending : myControlRequests.values()) {
            pending.completeExceptionally(exited);
        }
        myControlRequests.clear();
        Turn turn;
        synchronized (this) {
            turn = myTurn;
            myTurn = null;
        }
        if (turn != null) {
            turn.result().completeExceptionally(new IOException("claude exited with " + exitCode + ": " + stderrTail()));
        }
    }

    private synchronized String stderrTail() {
        return myStderr.isEmpty() ? "no output" : String.join("\n", myStderr);
    }

    private static AIUsage usage(@Nullable JsonObject usage) {
        if (usage == null) {
            return AIUsage.UNKNOWN;
        }
        return new AIUsage(integer(usage, "input_tokens"), integer(usage, "output_tokens"), integer(usage, "cache_read_input_tokens"));
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static int integer(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }
}

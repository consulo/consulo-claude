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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import consulo.ai.AIContent;
import consulo.ai.AIContextUsage;
import consulo.ai.AIMcpServer;
import consulo.ai.AIMcpServerProvider;
import consulo.ai.AIMessage;
import consulo.ai.AIPlanEntry;
import consulo.ai.AIPlanEntryPriority;
import consulo.ai.AIPlanEntryStatus;
import consulo.ai.AIResponse;
import consulo.ai.AIRole;
import consulo.ai.AIStopReason;
import consulo.ai.AIStreamListener;
import consulo.ai.AIUsage;
import consulo.application.Application;
import consulo.localize.LocalizeValue;
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
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * One conversation with the claude command line: a single long-lived {@code claude -p} process speaking stream-json,
 * which keeps the history on its side. A turn is written as one json line, the answer streams back as events and
 * ends with a {@code result} event.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
class ClaudeCliSession {
    private static final String EXIT_PLAN_MODE = "ExitPlanMode";

    private static final Logger LOG = Logger.getInstance(ClaudeCliSession.class);

    private static final int STDERR_TAIL = 20;
    private static final Pattern TASK_CREATED = Pattern.compile("Task #(\\S+) created");

    private record Turn(CompletableFuture<AIResponse> result,
                        @Nullable AIStreamListener listener,
                        StringBuilder text,
                        StringBuilder thinking,
                        Map<String, AIContent.ToolCall> toolCalls,
                        Map<String, String> toolNames) {
        Turn(CompletableFuture<AIResponse> result, @Nullable AIStreamListener listener) {
            this(result, listener, new StringBuilder(), new StringBuilder(), new LinkedHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private final ProcessHandler myHandler;
    private final Path myWorkingDirectory;
    /**
     * The tasks the command line keeps for the session ({@code TaskCreate} / {@code TaskUpdate}), by their id - the
     * plan of the chat.
     */
    private final Map<String, AIPlanEntry> myTasks = new LinkedHashMap<>();
    /**
     * A {@code TaskCreate} until its result tells the id of the task.
     */
    private final Map<String, String> myPendingTasks = new ConcurrentHashMap<>();
    private final StringBuilder myLine = new StringBuilder();
    private final Deque<String> myStderr = new ArrayDeque<>();
    private final ClaudeControlHandler myControlHandler = new ClaudeControlHandler(this::write, this::currentListener);

    private @Nullable Turn myTurn;
    private boolean myFresh;
    private @Nullable String myCliSessionId;
    private final Map<String, CompletableFuture<JsonObject>> myControlRequests = new ConcurrentHashMap<>();
    private boolean myTitleAsked;

    private ClaudeCliSession(ProcessHandler handler, Path workingDirectory) {
        myHandler = handler;
        myWorkingDirectory = workingDirectory;
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
        boolean resumed = sessionId != null && ClaudeChats.exists(workDirectory, sessionId);
        if (sessionId != null) {
            parameters.add(resumed ? "--resume" : "--session-id");
            parameters.add(sessionId);
        }
        String mcpConfig = mcpConfig();
        if (mcpConfig != null) {
            parameters.add("--mcp-config");
            parameters.add(mcpConfig);
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

        ProcessHandler handler = factory.newBuilder(commandLine).killable().silentReader().build();
        ClaudeCliSession session = new ClaudeCliSession(handler, workDirectory);
        session.myFresh = sessionId != null && !resumed;
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

    /**
     * The MCP servers of the IDE as the command line takes them - the model searches and navigates with the indexes of
     * the IDE through them. Null while there are none.
     */
    private static @Nullable String mcpConfig() {
        JsonObject servers = new JsonObject();
        Application.get().getExtensionPoint(AIMcpServerProvider.class).forEach(provider -> {
            for (AIMcpServer server : provider.getServers()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", "http");
                entry.addProperty("url", server.url());
                JsonObject headers = new JsonObject();
                server.headers().forEach(headers::addProperty);
                entry.add("headers", headers);
                servers.add(server.name(), entry);
            }
        });
        if (servers.isEmpty()) {
            return null;
        }
        JsonObject config = new JsonObject();
        config.add("mcpServers", servers);
        return config.toString();
    }

    /**
     * True for the first prompt of a session the command line did not keep - it knows nothing of the chat yet.
     */
    synchronized boolean takeFresh() {
        boolean fresh = myFresh;
        myFresh = false;
        return fresh;
    }

    synchronized CompletableFuture<AIResponse> send(String text, @Nullable AIStreamListener listener) {
        return send(List.of(AIContent.text(text)), listener);
    }

    /**
     * Sends a message of the user - its text, images, and the files and selections it carries, which the command line
     * takes as text: it has no blocks for them.
     */
    synchronized CompletableFuture<AIResponse> send(List<AIContent> content, @Nullable AIStreamListener listener) {
        if (myTurn != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The previous answer has not finished yet"));
        }
        if (myHandler.isProcessTerminated()) {
            return CompletableFuture.failedFuture(new IOException("claude exited: " + stderrTail()));
        }

        Turn turn = new Turn(new CompletableFuture<>(), listener);
        myTurn = turn;

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", blocks(content));
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

    private static JsonArray blocks(List<AIContent> content) {
        JsonArray blocks = new JsonArray();
        // the text of a selection goes after everything else, its link stays in place
        List<String> contexts = new ArrayList<>();
        for (AIContent part : content) {
            JsonObject block = new JsonObject();
            switch (part) {
                case AIContent.Text text -> {
                    block.addProperty("type", "text");
                    block.addProperty("text", text.text());
                }
                case AIContent.Image image -> {
                    JsonObject source = new JsonObject();
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", image.mediaType());
                    source.addProperty("data", Base64.getEncoder().encodeToString(image.data()));
                    block.addProperty("type", "image");
                    block.add("source", source);
                }
                case AIContent.ResourceLink link -> {
                    block.addProperty("type", "text");
                    block.addProperty("text", "[@" + link.name() + "](" + link.uri() + ")");
                }
                case AIContent.Resource resource -> {
                    String uri = resource.uri();
                    block.addProperty("type", "text");
                    block.addProperty("text", "[@" + uri.substring(uri.lastIndexOf('/') + 1) + "](" + uri + ")");
                    contexts.add("\n<context ref=\"" + uri + "\">\n" + resource.text() + "\n</context>");
                }
                default -> {
                    continue;
                }
            }
            blocks.add(block);
        }
        for (String context : contexts) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", context);
            blocks.add(block);
        }
        return blocks;
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
                if ("task_summary".equals(string(event, "subtype"))) {
                    onTaskSummary(string(event, "detail"));
                }
            }
            case "stream_event" -> onStreamEvent(event.getAsJsonObject("event"));
            case "assistant" -> onAssistant(event);
            case "user" -> onUser(event);
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
     * Switches the permission mode of the running session - the plan mode, for one.
     */
    CompletableFuture<?> setPermissionMode(ClaudePermissionMode mode) {
        JsonObject request = new JsonObject();
        request.addProperty("subtype", "set_permission_mode");
        request.addProperty("mode", mode.getCliMode());
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
                String thinking = string(delta, "thinking");
                turn.thinking().append(thinking);
                if (listener != null) {
                    listener.onThinkingDelta(thinking);
                }
            }
            default -> {
            }
        }
    }

    /**
     * What the command line is busy with - told as the status of the answer.
     */
    private void onTaskSummary(String detail) {
        AIStreamListener listener = currentListener();
        if (listener != null && !detail.isBlank()) {
            listener.onStatus(LocalizeValue.of(detail));
        }
    }

    /**
     * A message of the model, whole - its text came as stream events already, its tool calls start here.
     */
    private void onAssistant(JsonObject event) {
        Turn turn = myTurn;
        JsonObject message = ClaudeToolCalls.object(event, "message");
        JsonElement content = message.get("content");
        if (turn == null || content == null || !content.isJsonArray()) {
            return;
        }
        String parentId = string(event, "parent_tool_use_id");
        AIStreamListener listener = turn.listener();
        for (JsonElement element : content.getAsJsonArray()) {
            if (!element.isJsonObject() || !"tool_use".equals(string(element.getAsJsonObject(), "type"))) {
                continue;
            }
            JsonObject toolUse = element.getAsJsonObject();
            String name = string(toolUse, "name");
            String id = string(toolUse, "id");
            turn.toolNames().put(id, name);
            if (ClaudeToolCalls.PLAN_TOOLS.contains(name)) {
                onPlanTool(toolUse, listener);
                continue;
            }
            // the plan of the plan mode is a file - the chat opens it for review
            if (EXIT_PLAN_MODE.equals(name) && listener != null) {
                JsonObject input = toolUse.get("input") instanceof JsonObject object ? object : new JsonObject();
                String planFile = string(input, "planFilePath");
                if (!planFile.isEmpty()) {
                    listener.onPlanFile(Path.of(planFile));
                }
            }
            if (ClaudeToolCalls.HIDDEN_TOOLS.contains(name)) {
                continue;
            }
            AIContent.ToolCall call = ClaudeToolCalls.started(toolUse, parentId.isEmpty() ? null : parentId, myWorkingDirectory);
            synchronized (turn) {
                turn.toolCalls().put(id, call);
            }
            if (listener != null) {
                listener.onToolCall(call);
            }
        }
    }

    /**
     * The results of the tools - a user message the command line writes itself.
     */
    private void onUser(JsonObject event) {
        Turn turn = myTurn;
        JsonObject message = ClaudeToolCalls.object(event, "message");
        JsonElement content = message.get("content");
        if (turn == null || content == null || !content.isJsonArray()) {
            return;
        }
        AIStreamListener listener = turn.listener();
        JsonElement toolUseResult = event.get("tool_use_result");
        for (JsonElement element : content.getAsJsonArray()) {
            if (!element.isJsonObject() || !"tool_result".equals(string(element.getAsJsonObject(), "type"))) {
                continue;
            }
            JsonObject toolResult = element.getAsJsonObject();
            String id = string(toolResult, "tool_use_id");
            String name = turn.toolNames().getOrDefault(id, "");
            if ("TaskCreate".equals(name)) {
                JsonElement resultContent = toolResult.get("content");
                onTaskCreated(id, resultContent != null && resultContent.isJsonPrimitive() ? resultContent.getAsString() : "", toolUseResult, listener);
                continue;
            }
            AIContent.ToolCall finished;
            synchronized (turn) {
                AIContent.ToolCall call = turn.toolCalls().get(id);
                if (call == null) {
                    continue;
                }
                finished = ClaudeToolCalls.finished(call, name, toolResult, toolUseResult);
                turn.toolCalls().put(id, finished);
            }
            if (listener != null) {
                listener.onToolCall(finished);
            }
        }
    }

    private void onPlanTool(JsonObject toolUse, @Nullable AIStreamListener listener) {
        List<AIPlanEntry> todos = ClaudeToolCalls.todos(toolUse);
        if (todos != null) {
            if (listener != null) {
                listener.onPlan(todos);
            }
            return;
        }
        JsonObject input = ClaudeToolCalls.object(toolUse, "input");
        switch (string(toolUse, "name")) {
            case "TaskCreate" -> myPendingTasks.put(string(toolUse, "id"), string(input, "subject"));
            case "TaskUpdate" -> {
                String taskId = string(input, "taskId").isEmpty() ? string(input, "id") : string(input, "taskId");
                List<AIPlanEntry> plan;
                synchronized (myTasks) {
                    AIPlanEntry task = myTasks.get(taskId);
                    if (task == null) {
                        return;
                    }
                    String subject = string(input, "subject");
                    String status = string(input, "status");
                    myTasks.put(taskId, new AIPlanEntry(
                        subject.isEmpty() ? task.content() : subject,
                        task.priority(),
                        status.isEmpty() ? task.status() : ClaudeToolCalls.status(status)
                    ));
                    plan = List.copyOf(myTasks.values());
                }
                if (listener != null) {
                    listener.onPlan(plan);
                }
            }
            default -> {
            }
        }
    }

    private void onTaskCreated(String toolUseId, String resultText, @Nullable JsonElement toolUseResult, @Nullable AIStreamListener listener) {
        String subject = myPendingTasks.remove(toolUseId);
        JsonObject task = toolUseResult != null && toolUseResult.isJsonObject()
            ? ClaudeToolCalls.object(toolUseResult.getAsJsonObject(), "task")
            : new JsonObject();
        String taskId = string(task, "id");
        if (taskId.isEmpty()) {
            // older command lines tell the id in the text only: "Task #1 created successfully: ..."
            Matcher matcher = TASK_CREATED.matcher(resultText);
            taskId = matcher.find() ? matcher.group(1) : "";
        }
        if (subject == null || taskId.isEmpty()) {
            return;
        }
        List<AIPlanEntry> plan;
        synchronized (myTasks) {
            myTasks.put(taskId, new AIPlanEntry(subject, AIPlanEntryPriority.MEDIUM, AIPlanEntryStatus.PENDING));
            plan = List.copyOf(myTasks.values());
        }
        if (listener != null) {
            listener.onPlan(plan);
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
        AIStreamListener listener = turn.listener();
        AIContextUsage contextUsage = contextUsage(event);
        if (listener != null && contextUsage != null) {
            listener.onContextUsage(contextUsage);
        }
        List<AIContent> content = new ArrayList<>();
        if (!turn.thinking().isEmpty()) {
            content.add(AIContent.thinking(turn.thinking().toString()));
        }
        content.add(AIContent.text(text));
        synchronized (turn) {
            content.addAll(turn.toolCalls().values());
        }
        turn.result().complete(new AIResponse(new AIMessage(AIRole.ASSISTANT, content), AIStopReason.END_TURN, usage(event.getAsJsonObject("usage"))));
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

    /**
     * How full the context window of the model is after the turn - the prompt of the last request of the turn, out of
     * the window of the model, with what the turn cost.
     */
    private static @Nullable AIContextUsage contextUsage(JsonObject event) {
        JsonObject usage = ClaudeToolCalls.object(event, "usage");
        JsonElement iterations = usage.get("iterations");
        JsonObject last = usage;
        if (iterations != null && iterations.isJsonArray() && !iterations.getAsJsonArray().isEmpty()) {
            JsonElement element = iterations.getAsJsonArray().get(iterations.getAsJsonArray().size() - 1);
            if (element.isJsonObject()) {
                last = element.getAsJsonObject();
            }
        }
        long used = integer(last, "input_tokens") + integer(last, "cache_read_input_tokens")
            + integer(last, "cache_creation_input_tokens") + integer(last, "output_tokens");
        long size = 0;
        for (Map.Entry<String, JsonElement> model : ClaudeToolCalls.object(event, "modelUsage").entrySet()) {
            if (model.getValue().isJsonObject()) {
                size = Math.max(size, integer(model.getValue().getAsJsonObject(), "contextWindow"));
            }
        }
        if (size <= 0 || used <= 0) {
            return null;
        }
        JsonElement cost = event.get("total_cost_usd");
        return new AIContextUsage(used, size, cost != null && cost.isJsonPrimitive() ? cost.getAsDouble() : null, "USD");
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

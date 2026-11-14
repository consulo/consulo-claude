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
import consulo.ai.AIContent;
import consulo.ai.AIPlanEntry;
import consulo.ai.AIPlanEntryPriority;
import consulo.ai.AIPlanEntryStatus;
import consulo.ai.AIToolCallContent;
import consulo.ai.AIToolCallStatus;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The tools the command line runs, as tool calls of the chat - a {@code tool_use} block starts one, the
 * {@code tool_result} of the next user message ends it, and its {@code tool_use_result} tells what the tool did.
 *
 * @author VISTALL
 * @since 2026-11-14
 */
final class ClaudeToolCalls {
    /**
     * The tools which keep the list of the tasks of the turn - shown as the plan, not as calls.
     */
    static final Set<String> PLAN_TOOLS = Set.of("TodoWrite", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet");
    /**
     * Tools of the command line itself which do nothing for the user to see.
     */
    static final Set<String> HIDDEN_TOOLS = Set.of("ToolSearch");

    private static final int MAX_OUTPUT_LENGTH = 4000;

    private ClaudeToolCalls() {
    }

    /**
     * A call as the model asked for it - running until its result comes.
     */
    static AIContent.ToolCall started(JsonObject toolUse, @Nullable String parentId, Path workingDirectory) {
        String name = string(toolUse, "name");
        JsonObject input = object(toolUse, "input");
        String path = string(input, "file_path").isEmpty() ? string(input, "notebook_path") : string(input, "file_path");
        List<String> locations = path.isEmpty() ? List.of() : List.of(path);
        List<AIToolCallContent> content = new ArrayList<>();
        switch (name) {
            case "Edit" -> content.add(new AIToolCallContent.Diff(path, string(input, "old_string"), string(input, "new_string")));
            case "Write" -> content.add(new AIToolCallContent.Diff(path, null, string(input, "content")));
            default -> {
            }
        }
        return new AIContent.ToolCall(
            string(toolUse, "id"),
            parentId,
            title(name, input, path, workingDirectory),
            kind(name),
            AIToolCallStatus.IN_PROGRESS,
            locations,
            content,
            input.toString(),
            ""
        );
    }

    /**
     * The call once its result is there - the changes of an edit made whole from the file as it was before.
     */
    static AIContent.ToolCall finished(AIContent.ToolCall call, String name, JsonObject toolResult, @Nullable JsonElement toolUseResult) {
        boolean error = bool(toolResult, "is_error");
        JsonObject input = parse(call.inputJson());
        JsonObject result = toolUseResult != null && toolUseResult.isJsonObject() ? toolUseResult.getAsJsonObject() : new JsonObject();

        List<AIToolCallContent> content = new ArrayList<>(call.content());
        String output = "";
        if (!error && result.has("originalFile") && !call.locations().isEmpty()) {
            String path = call.locations().getFirst();
            String before = nullableString(result, "originalFile");
            String after = after(name, input, result, before);
            if (after != null) {
                content.clear();
                content.add(new AIToolCallContent.Diff(path, before, after));
            }
        }
        else if (result.has("stdout") || result.has("stderr")) {
            output = result.toString();
        }
        else if (!"Read".equals(name)) {
            String text = text(toolResult.get("content"));
            if (!text.isBlank()) {
                content.add(new AIToolCallContent.Text(truncate(text)));
            }
        }
        if (error) {
            String text = text(toolResult.get("content"));
            if (!text.isBlank() && content.stream().noneMatch(AIToolCallContent.Text.class::isInstance)) {
                content.add(new AIToolCallContent.Text(truncate(text)));
            }
        }
        return new AIContent.ToolCall(
            call.id(),
            call.parentId(),
            call.title(),
            call.kind(),
            error ? AIToolCallStatus.FAILED : AIToolCallStatus.COMPLETED,
            call.locations(),
            content,
            call.inputJson(),
            output
        );
    }

    /**
     * The plan a {@code TodoWrite} tells - the whole list every time.
     */
    static @Nullable List<AIPlanEntry> todos(JsonObject toolUse) {
        if (!"TodoWrite".equals(string(toolUse, "name"))) {
            return null;
        }
        JsonElement todos = object(toolUse, "input").get("todos");
        if (todos == null || !todos.isJsonArray()) {
            return null;
        }
        List<AIPlanEntry> entries = new ArrayList<>();
        for (JsonElement element : todos.getAsJsonArray()) {
            if (element.isJsonObject()) {
                JsonObject todo = element.getAsJsonObject();
                String status = string(todo, "status");
                // a running task reads as what is being done - "Running the tests" rather than "Run the tests"
                String text = "in_progress".equals(status) && !string(todo, "activeForm").isEmpty() ? string(todo, "activeForm") : string(todo, "content");
                entries.add(new AIPlanEntry(text, AIPlanEntryPriority.MEDIUM, status(status)));
            }
        }
        return entries;
    }

    static AIPlanEntryStatus status(String status) {
        return switch (status) {
            case "in_progress" -> AIPlanEntryStatus.IN_PROGRESS;
            case "completed" -> AIPlanEntryStatus.COMPLETED;
            default -> AIPlanEntryStatus.PENDING;
        };
    }

    /**
     * The text of the file after the tool, from the text before it - the result tells the old text only.
     */
    private static @Nullable String after(String name, JsonObject input, JsonObject result, @Nullable String before) {
        if ("Write".equals(name)) {
            return string(input, "content");
        }
        if (before == null) {
            return null;
        }
        if ("Edit".equals(name)) {
            return replace(before, string(input, "old_string"), string(input, "new_string"), bool(input, "replace_all"));
        }
        if ("MultiEdit".equals(name)) {
            JsonElement edits = input.get("edits");
            if (edits == null || !edits.isJsonArray()) {
                return null;
            }
            String text = before;
            for (JsonElement element : edits.getAsJsonArray()) {
                if (element.isJsonObject()) {
                    JsonObject edit = element.getAsJsonObject();
                    text = replace(text, string(edit, "old_string"), string(edit, "new_string"), bool(edit, "replace_all"));
                }
            }
            return text;
        }
        return null;
    }

    private static String replace(String text, String oldString, String newString, boolean all) {
        if (oldString.isEmpty()) {
            return text;
        }
        if (all) {
            return text.replace(oldString, newString);
        }
        int index = text.indexOf(oldString);
        return index < 0 ? text : text.substring(0, index) + newString + text.substring(index + oldString.length());
    }

    private static String title(String name, JsonObject input, String path, Path workingDirectory) {
        String file = path.isEmpty() ? "" : relative(path, workingDirectory);
        return switch (name) {
            case "Bash" -> string(input, "description").isEmpty() ? "`" + firstLine(string(input, "command")) + "`" : string(input, "description");
            case "Read" -> "Read " + file;
            case "Edit", "MultiEdit" -> "Edit " + file;
            case "Write" -> "Write " + file;
            case "NotebookEdit" -> "Edit " + file;
            case "Glob" -> "Find " + string(input, "pattern");
            case "Grep" -> "Search " + string(input, "pattern");
            case "WebFetch" -> "Fetch " + string(input, "url");
            case "WebSearch" -> "Search the web for " + string(input, "query");
            case "Task", "Agent" -> string(input, "description").isEmpty() ? name : string(input, "description");
            default -> name;
        };
    }

    private static String kind(String name) {
        return switch (name) {
            case "Bash" -> "execute";
            case "Read" -> "read";
            case "Edit", "MultiEdit", "Write", "NotebookEdit" -> "edit";
            case "Glob", "Grep" -> "search";
            case "WebFetch", "WebSearch" -> "fetch";
            case "Task", "Agent" -> AIContent.ToolCall.KIND_TASK;
            default -> "other";
        };
    }

    private static String relative(String path, Path workingDirectory) {
        try {
            Path file = Path.of(path);
            return file.startsWith(workingDirectory) ? workingDirectory.relativize(file).toString() : path;
        }
        catch (RuntimeException e) {
            return path;
        }
    }

    private static String text(@Nullable JsonElement content) {
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        StringBuilder text = new StringBuilder();
        if (content.isJsonArray()) {
            for (JsonElement block : content.getAsJsonArray()) {
                if (block.isJsonObject() && "text".equals(string(block.getAsJsonObject(), "type"))) {
                    text.append(string(block.getAsJsonObject(), "text"));
                }
            }
        }
        return text.toString();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_OUTPUT_LENGTH ? text : text.substring(0, MAX_OUTPUT_LENGTH) + "\u2026";
    }

    private static String firstLine(String text) {
        int newLine = text.indexOf('\n');
        return newLine < 0 ? text : text.substring(0, newLine) + " \u2026";
    }

    static JsonObject parse(String json) {
        try {
            JsonElement element = com.google.gson.JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        }
        catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static JsonObject object(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static @Nullable String nullableString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    static boolean bool(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}

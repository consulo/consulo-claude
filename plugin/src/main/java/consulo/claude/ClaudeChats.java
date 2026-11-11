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
import consulo.ai.AIChatInfo;
import consulo.ai.AIContent;
import consulo.ai.AIMessage;
import consulo.ai.AIRole;
import consulo.logging.Logger;
import consulo.platform.Platform;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The chats the claude command line keeps itself - a {@code <session id>.jsonl} file per chat under
 * {@code ~/.claude/projects/<working directory>}, one record a line. Only the records of the conversation and of its
 * title - {@code custom-title} the user gave, {@code ai-title} the command line generated, the last of each winning - are
 * read; the format belongs to the command line, so whatever is not known is skipped.
 *
 * @author VISTALL
 * @since 2026-11-10
 */
final class ClaudeChats {
    private static final Logger LOG = Logger.getInstance(ClaudeChats.class);

    private static final String EXTENSION = ".jsonl";
    private static final int TITLE_LENGTH = 40;
    /**
     * How many sessions of other directories than the one of the project are listed - the newest.
     */
    private static final int MAX_OTHER_CHATS = 100;
    private static final int HEAD_BYTES = 256 * 1024;
    private static final int TAIL_BYTES = 512 * 1024;
    private static final int MAX_RESULT_LENGTH = 4000;

    private record CachedInfo(long size, long modified, @Nullable AIChatInfo info) {
    }

    private static final Map<Path, CachedInfo> ourInfos = new ConcurrentHashMap<>();

    /**
     * Where the command line keeps the chats which ran in the directory - every character but a letter or a digit of
     * the path turns into a dash.
     */
    static Path directory(Path workingDirectory) {
        String name = workingDirectory.toAbsolutePath().normalize().toString().replaceAll("[^A-Za-z0-9]", "-");
        return configDirectory().resolve("projects").resolve(name);
    }

    /**
     * Whether the command line can resume the session from the directory - it looks for it there only.
     */
    static boolean exists(Path workingDirectory, String sessionId) {
        return Files.isRegularFile(directory(workingDirectory).resolve(sessionId + EXTENSION));
    }

    /**
     * The sessions of the command line, newest first - every one which ran in the directory, and the newest of the
     * others, wherever they ran. Blocks while the files are read - call it off the ui thread.
     */
    static List<AIChatInfo> list(Path workingDirectory) {
        Path projects = configDirectory().resolve("projects");
        if (!Files.isDirectory(projects)) {
            return List.of();
        }
        Path own = directory(workingDirectory);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> directories = Files.list(projects)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                try (Stream<Path> sessions = Files.list(directory)) {
                    sessions.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).forEach(files::add);
                }
            }
        }
        catch (IOException e) {
            LOG.warn("Cannot list the chats in " + projects, e);
        }
        files.sort(Comparator.comparing(ClaudeChats::lastModified).reversed());

        List<AIChatInfo> chats = new ArrayList<>();
        int others = 0;
        for (Path file : files) {
            boolean ownDirectory = own.equals(file.getParent());
            if (!ownDirectory && others >= MAX_OTHER_CHATS) {
                continue;
            }
            AIChatInfo chat = info(file);
            if (chat != null) {
                chats.add(chat);
                if (!ownDirectory) {
                    others++;
                }
            }
        }
        return chats;
    }

    static @Nullable AIChatInfo find(String sessionId) {
        Path file = file(sessionId);
        return file == null ? null : info(file);
    }

    /**
     * The conversation of the session, read a line at a time - a session runs to hundreds of megabytes. Blocks while
     * the file is read - call it off the ui thread.
     */
    static List<AIMessage> load(String sessionId) {
        Path file = file(sessionId);
        if (file == null) {
            return List.of();
        }
        List<AIMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\":\"assistant\"")) {
                    continue;
                }
                JsonObject record = parse(line);
                AIMessage message = record == null ? null : message(record);
                if (message != null) {
                    messages.add(message);
                }
            }
        }
        catch (IOException | UncheckedIOException e) {
            LOG.warn("Cannot read the chat " + file, e);
        }
        return messages;
    }

    /**
     * Names the chat the way the command line itself does, so it shows the name too.
     */
    static void rename(String sessionId, String title) throws IOException {
        Path file = file(sessionId);
        if (file == null) {
            return;
        }
        JsonObject record = new JsonObject();
        record.addProperty("type", "custom-title");
        record.addProperty("customTitle", title);
        record.addProperty("sessionId", sessionId);
        Files.writeString(file, record + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    static void delete(String sessionId) throws IOException {
        Path file = file(sessionId);
        if (file != null) {
            Files.delete(file);
        }
    }

    /**
     * What the session is, read from its start - the first prompt, where it ran, whether it holds a conversation at all
     * - and from its end, where the command line keeps appending the title. Kept while the file does not change.
     */
    private static @Nullable AIChatInfo info(Path file) {
        long size;
        long modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        }
        catch (IOException e) {
            return null;
        }
        CachedInfo cached = ourInfos.get(file);
        if (cached != null && cached.size() == size && cached.modified() == modified) {
            return cached.info();
        }
        AIChatInfo info = readInfo(file, size, modified);
        ourInfos.put(file, new CachedInfo(size, modified, info));
        return info;
    }

    private static @Nullable AIChatInfo readInfo(Path file, long size, long modified) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - EXTENSION.length());

        List<String> head = lines(file, 0, Math.min(size, HEAD_BYTES));
        List<String> tail = size > HEAD_BYTES ? lines(file, Math.max(HEAD_BYTES, size - TAIL_BYTES), size) : List.of();

        Path cwd = null;
        String firstPrompt = null;
        boolean conversation = false;
        for (String line : head) {
            boolean user = line.contains("\"type\":\"user\"");
            if (!user && !line.contains("\"type\":\"assistant\"")) {
                continue;
            }
            JsonObject record = parse(line);
            if (record == null || isSidechain(record)) {
                continue;
            }
            conversation = true;
            String recordCwd = string(record, "cwd");
            if (cwd == null && !recordCwd.isEmpty()) {
                cwd = Path.of(recordCwd);
            }
            if (firstPrompt == null && user) {
                AIMessage message = message(record);
                String text = message == null ? "" : message.getText().strip();
                if (!text.isEmpty() && !text.startsWith("<")) {
                    firstPrompt = firstLine(text);
                }
            }
            if (cwd != null && firstPrompt != null) {
                break;
            }
        }
        if (!conversation) {
            return null;
        }

        // the last title wins - looked for at the end, where it is appended, then at the start
        String customTitle = lastValue(tail, "custom-title", "customTitle");
        if (customTitle == null) {
            customTitle = lastValue(head, "custom-title", "customTitle");
        }
        String aiTitle = lastValue(tail, "ai-title", "aiTitle");
        if (aiTitle == null) {
            aiTitle = lastValue(head, "ai-title", "aiTitle");
        }
        String agentName = lastValue(tail, "agent-name", "agentName");
        if (agentName == null) {
            agentName = lastValue(head, "agent-name", "agentName");
        }

        // the name the user gave wins over the one the command line generated
        String title = customTitle != null ? customTitle
            : aiTitle != null ? aiTitle
            : agentName != null ? agentName
            : firstPrompt != null ? firstPrompt : "";
        return new AIChatInfo(id, title, Instant.ofEpochMilli(modified), cwd);
    }

    private static @Nullable String lastValue(List<String> lines, String type, String field) {
        String marker = "\"type\":\"" + type + "\"";
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (!line.contains(marker)) {
                continue;
            }
            JsonObject record = parse(line);
            String value = record == null ? "" : string(record, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * The whole lines between the offsets - a line cut by either end is dropped.
     */
    private static List<String> lines(Path file, long from, long to) {
        int length = (int) (to - from);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(from);
            int read = 0;
            while (buffer.hasRemaining() && read >= 0) {
                read = channel.read(buffer);
            }
        }
        catch (IOException e) {
            LOG.warn("Cannot read the chat " + file, e);
            return List.of();
        }
        String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (from > 0 && !lines.isEmpty()) {
            lines.removeFirst();
        }
        if (!lines.isEmpty()) {
            lines.removeLast();
        }
        return lines;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        }
        catch (IOException e) {
            return 0;
        }
    }

    private static @Nullable AIMessage message(JsonObject record) {
        String type = string(record, "type");
        if (!"user".equals(type) && !"assistant".equals(type) || isSidechain(record) || bool(record, "isMeta")) {
            return null;
        }
        JsonElement messageElement = record.get("message");
        if (messageElement == null || !messageElement.isJsonObject()) {
            return null;
        }
        JsonObject message = messageElement.getAsJsonObject();
        AIRole role = "assistant".equals(type) ? AIRole.ASSISTANT : AIRole.USER;

        List<AIContent> content = new ArrayList<>();
        JsonElement contentElement = message.get("content");
        if (contentElement != null && contentElement.isJsonPrimitive()) {
            content.add(AIContent.text(contentElement.getAsString()));
        }
        else if (contentElement != null && contentElement.isJsonArray()) {
            for (JsonElement blockElement : contentElement.getAsJsonArray()) {
                if (!blockElement.isJsonObject()) {
                    continue;
                }
                JsonObject block = blockElement.getAsJsonObject();
                switch (string(block, "type")) {
                    case "text" -> content.add(AIContent.text(string(block, "text")));
                    case "tool_use" -> {
                        JsonElement input = block.get("input");
                        content.add(AIContent.toolUse(string(block, "id"), string(block, "name"), input == null ? "{}" : input.toString()));
                    }
                    case "tool_result" -> content.add(AIContent.toolResult(
                        string(block, "tool_use_id"),
                        truncate(resultText(block.get("content"))),
                        bool(block, "is_error")
                    ));
                    default -> {
                    }
                }
            }
        }
        return content.isEmpty() ? null : new AIMessage(role, content);
    }

    /**
     * A tool result is kept for what the model saw, not shown in full - a file it read can be large.
     */
    private static String truncate(String text) {
        return text.length() <= MAX_RESULT_LENGTH ? text : text.substring(0, MAX_RESULT_LENGTH) + "\u2026";
    }

    private static String resultText(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : (JsonArray) element) {
                if (part.isJsonObject()) {
                    text.append(string(part.getAsJsonObject(), "text"));
                }
            }
            return text.toString();
        }
        return element.toString();
    }

    private static @Nullable JsonObject parse(String line) {
        try {
            JsonElement element = JsonParser.parseString(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e) {
            // a line being written right now, or a record of a format we do not know
            return null;
        }
    }

    /**
     * The file of the session in whichever directory it ran - a chat can work in another directory than the project.
     */
    private static @Nullable Path file(String sessionId) {
        Path projects = configDirectory().resolve("projects");
        if (!Files.isDirectory(projects)) {
            return null;
        }
        try (Stream<Path> directories = Files.list(projects)) {
            for (Path directory : directories.toList()) {
                Path file = directory.resolve(sessionId + EXTENSION);
                if (Files.isRegularFile(file)) {
                    return file;
                }
            }
        }
        catch (IOException e) {
            LOG.warn("Cannot look for the chat " + sessionId, e);
        }
        return null;
    }

    private static Path configDirectory() {
        String configured = Platform.current().os().getEnvironmentVariable("CLAUDE_CONFIG_DIR");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Platform.current().user().homePath().resolve(".claude");
    }

    private static boolean isSidechain(JsonObject record) {
        return bool(record, "isSidechain");
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static String string(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static @Nullable String nonEmpty(String value, @Nullable String previous) {
        return value.isBlank() ? previous : value;
    }

    private static String firstLine(String text) {
        String line = text.lines().findFirst().orElse("").strip();
        return line.length() <= TITLE_LENGTH ? line : line.substring(0, TITLE_LENGTH).strip() + "…";
    }

    private ClaudeChats() {
    }
}

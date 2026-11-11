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

import consulo.ai.AIEffortLevel;
import consulo.ai.AIModel;
import consulo.ai.AIModelCapability;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;

import java.util.List;
import java.util.Set;

/**
 * A model as the claude command line names it - an alias it resolves itself.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
record ClaudeModel(String id, String displayName, String description, int contextWindow, List<AIEffortLevel> effortLevels)
    implements AIModel {
    /**
     * Leaves the choice to the command line and the account - no {@code --model} is passed.
     */
    static final String DEFAULT_ID = "default";

    ClaudeModel(String id, String displayName, int contextWindow) {
        this(id, displayName, "", contextWindow, List.of());
    }

    /**
     * The levels as {@code --effort} takes them, named for the user.
     */
    static List<AIEffortLevel> effortLevels(List<String> ids) {
        return ids.stream().map(id -> new AIEffortLevel(id, effortName(id))).toList();
    }

    private static LocalizeValue effortName(String id) {
        return switch (id) {
            case "low" -> ClaudeLocalize.effortLow();
            case "medium" -> ClaudeLocalize.effortMedium();
            case "high" -> ClaudeLocalize.effortHigh();
            case "xhigh" -> ClaudeLocalize.effortXhigh();
            case "max" -> ClaudeLocalize.effortMax();
            default -> LocalizeValue.of(id);
        };
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getProviderTypeId() {
        return ClaudeAIProviderType.ID;
    }

    @Override
    public LocalizeValue getDisplayName() {
        return LocalizeValue.of(displayName);
    }

    @Override
    public LocalizeValue getDescription() {
        return LocalizeValue.of(description);
    }

    @Override
    public int getContextWindow() {
        return contextWindow;
    }

    @Override
    public List<AIEffortLevel> getEffortLevels() {
        return effortLevels;
    }

    @Override
    public Set<AIModelCapability> getCapabilities() {
        return Set.of(AIModelCapability.STREAMING, AIModelCapability.THINKING);
    }
}

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

import consulo.ai.AIAgentInstructionProvider;
import consulo.ai.AIAgentRuleDirectory;
import consulo.annotation.component.ExtensionImpl;
import consulo.localize.LocalizeValue;

import java.util.List;

/**
 * Where Claude reads the instructions of a project.
 *
 * @author VISTALL
 * @since 2026-11-14
 */
@ExtensionImpl
public class ClaudeAgentInstructionProvider implements AIAgentInstructionProvider {
    @Override
    public LocalizeValue getName() {
        return LocalizeValue.of("Claude");
    }

    @Override
    public List<String> getInstructionFiles() {
        return List.of("CLAUDE.md");
    }

    @Override
    public List<AIAgentRuleDirectory> getRuleDirectories() {
        return List.of(new AIAgentRuleDirectory(".claude/rules", List.of(".md"), """
            # {name}

            Your rule content here.
            """));
    }

    @Override
    public List<String> getSkillDirectories() {
        return List.of(".claude/skills");
    }
}

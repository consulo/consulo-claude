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

import consulo.ai.AIProvider;
import consulo.claude.localize.ClaudeLocalize;
import consulo.localize.LocalizeValue;

import java.util.List;
import java.util.Map;

/**
 * When the command line asks before it runs a tool. Kept as the {@value #OPTION} option of the account.
 *
 * @author VISTALL
 * @since 2026-10-13
 */
enum ClaudePermissionMode {
    MANUAL("manual", ClaudeLocalize.permissionModeManualShort(), ClaudeLocalize.permissionModeManual()),
    ACCEPT_EDITS("acceptEdits", ClaudeLocalize.permissionModeAcceptEditsShort(), ClaudeLocalize.permissionModeAcceptEdits()),
    PLAN("plan", ClaudeLocalize.permissionModePlanShort(), ClaudeLocalize.permissionModePlan()),
    AUTO("auto", ClaudeLocalize.permissionModeAutoShort(), ClaudeLocalize.permissionModeAuto()),
    DONT_ASK("dontAsk", ClaudeLocalize.permissionModeDontAskShort(), ClaudeLocalize.permissionModeDontAsk()),
    BYPASS("bypassPermissions", ClaudeLocalize.permissionModeBypassShort(), ClaudeLocalize.permissionModeBypass());

    static final String OPTION = "permissionMode";

    private final String myId;
    private final LocalizeValue myShortName;
    private final LocalizeValue myDisplayName;

    ClaudePermissionMode(String id, LocalizeValue shortName, LocalizeValue displayName) {
        myId = id;
        myShortName = shortName;
        myDisplayName = displayName;
    }

    /**
     * The name on the mode button of a chat - the display name tells what the mode does.
     */
    LocalizeValue getShortName() {
        return myShortName;
    }

    LocalizeValue getDisplayName() {
        return myDisplayName;
    }

    String getId() {
        return myId;
    }

    /**
     * The name the command line knows the mode by, for a switch of a running session.
     */
    String getCliMode() {
        return this == MANUAL ? "default" : myId;
    }

    static ClaudePermissionMode ofId(String id) {
        return of(Map.of(OPTION, id));
    }

    List<String> getParameters() {
        return switch (this) {
            case MANUAL -> List.of();
            case BYPASS -> List.of("--dangerously-skip-permissions");
            default -> List.of("--permission-mode", myId);
        };
    }

    void store(Map<String, String> options) {
        options.put(OPTION, myId);
    }

    static ClaudePermissionMode of(AIProvider provider) {
        return of(provider.getOptions());
    }

    static ClaudePermissionMode of(Map<String, String> options) {
        String id = options.get(OPTION);
        for (ClaudePermissionMode mode : values()) {
            if (mode.myId.equals(id)) {
                return mode;
            }
        }
        return MANUAL;
    }
}

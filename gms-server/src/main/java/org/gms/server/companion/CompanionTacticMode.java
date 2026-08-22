package org.gms.server.companion;

public enum CompanionTacticMode {
    SUPPORT_ONLY(0, "纯辅助模式 (维持Buff与治疗)"),
    BALANCED_COMBAT(1, "平衡战斗模式 (跟随攻击+维持Buff)"),
    STATIONARY_GUARD(2, "定点防守模式 (原地守怪)");

    private final int code;
    private final String description;

    CompanionTacticMode(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static CompanionTacticMode fromCode(int code) {
        for (CompanionTacticMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        return SUPPORT_ONLY;
    }
}

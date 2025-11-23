package org.markski.blocklog;

public enum BlockActionCause {
    PLAYER(1),
    EXPLOSION(2),
    PISTON(3),
    MOB(4);

    private final int code;

    BlockActionCause(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BlockActionCause fromCode(int code) {
        for (BlockActionCause value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown BlockActionCause code: " + code);
    }
}
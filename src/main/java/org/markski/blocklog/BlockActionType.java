package org.markski.blocklog;

public enum BlockActionType {
    PLACED(1),
    BROKEN(2),
    INTERACTION(3);

    private final int code;

    BlockActionType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BlockActionType fromCode(int code) {
        for (BlockActionType value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown BlockActionType code: " + code);
    }
}
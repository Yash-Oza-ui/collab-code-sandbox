package com.collabcode.syncservice.room;

/**
 * The two room modes (T-17 V2.1, decision 1; FR17). Chosen at creation and
 * immutable afterwards.
 */
public enum RoomMode {
    PAIR("pair"),
    OPEN("open");

    private final String value;

    RoomMode(String value) {
        this.value = value;
    }

    /** The lowercase wire value used in the contract ("pair" / "open"). */
    public String value() {
        return value;
    }

    /** Parse a wire value; returns {@code null} for an unknown or null value. */
    public static RoomMode fromValue(String v) {
        if (v == null) {
            return null;
        }
        for (RoomMode m : values()) {
            if (m.value.equals(v)) {
                return m;
            }
        }
        return null;
    }
}

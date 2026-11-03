package com.collabcode.syncservice.room;

/**
 * The three room roles (T-17 V2.4, decision 5). The client takes a member's
 * role from the server's {@code roster} message, never from awareness.
 */
public enum Role {
    HOST("host"),
    PARTICIPANT("participant"),
    OBSERVER("observer");

    private final String value;

    Role(String value) {
        this.value = value;
    }

    /** The lowercase wire value used in the contract. */
    public String value() {
        return value;
    }
}

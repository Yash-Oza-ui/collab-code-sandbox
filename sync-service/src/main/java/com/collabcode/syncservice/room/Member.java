package com.collabcode.syncservice.room;

/**
 * A member of a room (T-17 V2.3, V2.4).
 *
 * <p>{@code memberId} is the public, per-room id used in {@code roster},
 * module data and REST targets. {@code sessionId} is the caller's private
 * credential (v0 §3.3) and is <strong>never</strong> broadcast, so one member
 * cannot learn another's session id and impersonate them.
 */
public class Member {

    private final String memberId;
    private final String sessionId;   // private; never put in roster or any broadcast
    private final String displayName;
    private Role role;
    private boolean canEdit;
    private boolean canRun;

    public Member(String memberId, String sessionId, String displayName,
                  Role role, boolean canEdit, boolean canRun) {
        this.memberId = memberId;
        this.sessionId = sessionId;
        this.displayName = displayName;
        this.role = role;
        this.canEdit = canEdit;
        this.canRun = canRun;
    }

    public String getMemberId() { return memberId; }
    public String getSessionId() { return sessionId; }
    public String getDisplayName() { return displayName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public boolean isCanEdit() { return canEdit; }
    public void setCanEdit(boolean canEdit) { this.canEdit = canEdit; }

    public boolean isCanRun() { return canRun; }
    public void setCanRun(boolean canRun) { this.canRun = canRun; }
}

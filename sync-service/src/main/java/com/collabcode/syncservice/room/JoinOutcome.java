package com.collabcode.syncservice.room;

/**
 * Result of a join attempt (T-17 V2.3): either the member joined, or the join
 * was refused with a reason (for example {@code NAME_TAKEN}).
 */
public final class JoinOutcome {

    public enum Status { JOINED, REFUSED }

    private final Status status;
    private final Member member;        // present when JOINED
    private final String refusedReason; // present when REFUSED

    private JoinOutcome(Status status, Member member, String refusedReason) {
        this.status = status;
        this.member = member;
        this.refusedReason = refusedReason;
    }

    public static JoinOutcome joined(Member member) {
        return new JoinOutcome(Status.JOINED, member, null);
    }

    public static JoinOutcome refused(String reason) {
        return new JoinOutcome(Status.REFUSED, null, reason);
    }

    public boolean isJoined() { return status == Status.JOINED; }
    public Status getStatus() { return status; }
    public Member getMember() { return member; }
    public String getRefusedReason() { return refusedReason; }
}

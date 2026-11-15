package com.collabcode.execution.snapshot;

/**
 * Member identity pair – T-17 V2.8, V2.9, V2.11.
 */
public class MemberInfo {

    private String member_id;
    private String display_name;

    public MemberInfo() {}

    public MemberInfo(String member_id, String display_name) {
        this.member_id = member_id;
        this.display_name = display_name;
    }

    public String getMember_id() { return member_id; }
    public void setMember_id(String member_id) { this.member_id = member_id; }

    public String getDisplay_name() { return display_name; }
    public void setDisplay_name(String display_name) { this.display_name = display_name; }
}


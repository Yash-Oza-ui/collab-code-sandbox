package com.collabcode.execution.permission;

/**
 * Result of internal session lookup from sync-service – T-17 V2.11.
 */
public class PermissionLookupResult {

    private String member_id;
    private String display_name;
    private String role;
    private String mode;
    private String language;
    private boolean can_run;
    private String run_denied_reason;
    private boolean ai_enabled;

    public PermissionLookupResult() {}

    public PermissionLookupResult(
            String member_id,
            String display_name,
            String role,
            String mode,
            String language,
            boolean can_run,
            String run_denied_reason,
            boolean ai_enabled) {
        this.member_id = member_id;
        this.display_name = display_name;
        this.role = role;
        this.mode = mode;
        this.language = language;
        this.can_run = can_run;
        this.run_denied_reason = run_denied_reason;
        this.ai_enabled = ai_enabled;
    }

    public String getMember_id() { return member_id; }
    public void setMember_id(String member_id) { this.member_id = member_id; }

    public String getDisplay_name() { return display_name; }
    public void setDisplay_name(String display_name) { this.display_name = display_name; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public boolean isCan_run() { return can_run; }
    public void setCan_run(boolean can_run) { this.can_run = can_run; }

    public String getRun_denied_reason() { return run_denied_reason; }
    public void setRun_denied_reason(String run_denied_reason) { this.run_denied_reason = run_denied_reason; }

    public boolean isAi_enabled() { return ai_enabled; }
    public void setAi_enabled(boolean ai_enabled) { this.ai_enabled = ai_enabled; }
}


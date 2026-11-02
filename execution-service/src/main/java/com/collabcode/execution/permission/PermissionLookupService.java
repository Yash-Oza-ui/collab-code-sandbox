package com.collabcode.execution.permission;

/**
 * Permission lookup interface – Backlog v2 §6.1, Decision 11 (T-17 V2.11).
 *
 * execution-service asks sync-service who the caller is and what the caller may do.
 * execution-service keeps no permission state and never caches an answer (FR41.4).
 */
public interface PermissionLookupService {

    /**
     * Looks up member identity and room permissions for the given room and session.
     *
     * @param roomId    the room identifier
     * @param sessionId the caller's session id
     * @return {@link PermissionLookupResult} with role, mode, permissions
     */
    PermissionLookupResult lookup(String roomId, String sessionId);
}


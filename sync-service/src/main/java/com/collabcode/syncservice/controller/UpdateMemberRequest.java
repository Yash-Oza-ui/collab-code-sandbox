package com.collabcode.syncservice.controller;

/**
 * Body of {@code PATCH /v1/rooms/{room_id}/members/{member_id}} (US-27, V2.17).
 * {@code role} is {@code "participant"} or {@code "observer"}; {@code "host"}
 * is not accepted (transferring the Host role, FR31.1, is a separate story).
 */
public record UpdateMemberRequest(String role) {
}

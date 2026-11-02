package com.collabcode.syncservice.controller;

/**
 * Body of {@code POST /v1/rooms} (T-17 V2.1).
 *
 * <p>All fields are optional at the JSON layer and validated in the
 * controller: {@code mode} is required; {@code preset} is Pair-only;
 * {@code language} is Open-only. Any unknown field (for example a
 * client-supplied {@code room_id}) is ignored.
 */
public record CreateRoomRequest(String mode, String preset, String language) {
}

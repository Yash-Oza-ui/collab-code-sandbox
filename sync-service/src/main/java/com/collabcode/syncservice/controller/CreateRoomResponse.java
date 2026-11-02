package com.collabcode.syncservice.controller;

/**
 * {@code 201 Created} response of {@code POST /v1/rooms} (T-17 V2.1).
 *
 * <p>{@code owner_token} is returned once here and never again; the server
 * keeps only its hash. {@code preset} is {@code null} in Open mode and
 * {@code language} is {@code null} in Pair mode.
 */
public record CreateRoomResponse(
        String room_id,
        String owner_token,
        String mode,
        String preset,
        String language) {
}

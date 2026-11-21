package com.collabcode.syncservice.room;

import com.collabcode.syncservice.SyncServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Spring's real scheduler invokes the Host departure checker.
 * This test deliberately never calls checkExpiredDepartures().
 */
@SpringBootTest(
        classes = SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.room.limits.host-leaves-timeout-ms=100",
                "app.room.limits.host-leaves-check-interval-ms=10"
        }
)
class HostDepartureSchedulingIntegrationTest {

    @Autowired
    private RoomStore roomStore;

    @Autowired
    private RoomService roomService;

    @Autowired
    private HostDepartureManager hostDepartureManager;

    @Test
    void scheduledCheckerAutomaticallyClosesRoomAfterHostDeparture() throws InterruptedException {
        String roomId = "scheduler-" + UUID.randomUUID().toString().replace("-", "");
        String ownerToken = "scheduler-test-owner";
        Room room = new Room(
                roomId,
                RoomMode.PAIR,
                "debug_together",
                null,
                OwnerTokens.hash(ownerToken)
        );
        roomStore.put(room);

        assertThat(roomService.join(roomId, "scheduler-host-session", "Host", ownerToken).isJoined())
                .isTrue();

        // Register the departure. The scheduled method—not this test—must expire it.
        hostDepartureManager.hostDisconnected(roomId, "scheduler-host-session");

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!room.isClosed() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(room.isClosed())
                .as("Spring's scheduled checker should close the room after the Host timeout")
                .isTrue();
    }
}

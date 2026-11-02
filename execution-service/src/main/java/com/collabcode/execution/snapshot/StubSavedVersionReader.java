package com.collabcode.execution.snapshot;

import com.collabcode.execution.exception.RoomServiceUnavailableException;
import com.collabcode.execution.model.SavedSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic in-memory stub for SavedVersionReader (T-19, Jay).
 *
 * Reads all module versions together in one call (the one-call snapshot rule).
 * Returns ROOM_SERVICE_UNAVAILABLE (503) when simulated failure is active.
 */
@Component
public class StubSavedVersionReader implements SavedVersionReader {

    private volatile boolean available = true;
    private volatile SnapshotResult customSnapshot = null;

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setCustomSnapshot(SnapshotResult customSnapshot) {
        this.customSnapshot = customSnapshot;
    }

    public void reset() {
        this.available = true;
        this.customSnapshot = null;
    }

    @Override
    public SnapshotResult readSnapshot(String roomId, SavedSource source) {
        if (!available || "service-unavailable".equalsIgnoreCase(roomId)) {
            throw new RoomServiceUnavailableException(null);
        }

        if (customSnapshot != null) {
            return customSnapshot;
        }

        MemberInfo ada = new MemberInfo("m_7a3c9f", "Ada");
        MemberInfo lin = new MemberInfo("m_4b19d2", "Lin");

        if ("module".equalsIgnoreCase(source.getTarget())) {
            String modId = source.getModule_id() != null ? source.getModule_id() : "mod_default";
            SnapshotModule mod = new SnapshotModule(
                    modId,
                    "main.py",
                    "print(\"hello from saved module\")\n",
                    1,
                    ada,
                    ada,
                    "2026-11-01T10:12:04.118Z"
            );
            return new SnapshotResult(roomId, "main.py", List.of(mod));
        }

        // target: "entry"
        List<SnapshotModule> modules = new ArrayList<>();
        SnapshotModule mainMod = new SnapshotModule(
                "mod_9Qx7mBvA2nR0",
                "main.py",
                "from helper import add\nprint(add(2, 3))\n",
                4,
                ada,
                ada,
                "2026-11-01T10:12:04.118Z"
        );
        SnapshotModule helperMod = new SnapshotModule(
                "mod_5Lm8Np2Qr4St",
                "helper.py",
                "def add(a, b):\n    return a + b\n",
                2,
                ada,
                lin,
                "2026-11-01T10:14:51.733Z"
        );

        if ("all".equals(source.getModule_ids())) {
            modules.add(mainMod);
            modules.add(helperMod);
        } else if (source.getModule_ids() instanceof List<?> list) {
            for (Object id : list) {
                if ("mod_5Lm8Np2Qr4St".equals(id)) {
                    modules.add(helperMod);
                } else {
                    modules.add(new SnapshotModule(
                            String.valueOf(id),
                            "main.py",
                            "print(\"hello from \" + '" + id + "')\n",
                            1,
                            ada,
                            ada,
                            "2026-11-01T10:12:04.118Z"
                    ));
                }
            }
        } else {
            modules.add(mainMod);
        }

        return new SnapshotResult(roomId, "main.py", modules);
    }
}


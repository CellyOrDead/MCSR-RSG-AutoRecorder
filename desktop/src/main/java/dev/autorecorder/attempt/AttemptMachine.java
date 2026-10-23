package dev.autorecorder.attempt;

import dev.autorecorder.model.Attempt;
import java.time.Instant;
import java.util.UUID;

/** Idempotent transition logic. Nether is a monotonic milestone. */
public final class AttemptMachine {
    public Attempt begin(UUID session, String world) {
        Instant now = Instant.now();
        return new Attempt(UUID.randomUUID(), session, world, now, null, false, false, null,
                "WORLD_LOADING", null, "PENDING", false, false, null);
    }
    public Attempt dimension(Attempt a, String dimension) {
        if (a == null || a.endedAt() != null) return a;
        if ("minecraft:the_nether".equals(dimension) || "the_nether".equals(dimension))
            return a.withNether(Instant.now());
        if ("minecraft:the_end".equals(dimension) || "the_end".equals(dimension))
            return a.withEndReached();
        if ("minecraft:overworld".equals(dimension) || "overworld".equals(dimension))
            return a.withState("IN_OVERWORLD", null);
        return a;
    }
    public Attempt finish(Attempt a, boolean reliable) {
        if (a == null || a.endedAt() != null) return a;
        // A clean world exit is not proof that the player stayed in the Overworld.
        // Only a confirmed Overworld signal makes a non-Nether attempt eligible.
        String state = a.netherReached() ? "KEEP"
                : !reliable ? "NEEDS_REVIEW"
                : "IN_OVERWORLD".equals(a.state()) ? "DELETE_PENDING" : "NEEDS_REVIEW";
        return a.withState(state, Instant.now());
    }

    /** @param netherEvidence TRUE/FALSE only when the allowed game-state record confirms it; null is unknown. */
    public Attempt finish(Attempt a, boolean reliable, Boolean netherEvidence) {
        if (a == null || a.endedAt() != null) return a;
        if (Boolean.TRUE.equals(netherEvidence) && !a.netherReached()) a = a.withNether(Instant.now());
        else if (Boolean.FALSE.equals(netherEvidence) && !a.netherReached()) a = a.withState("IN_OVERWORLD", null);
        return finish(a, reliable);
    }
}

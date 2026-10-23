package dev.autorecorder.retention;

import dev.autorecorder.model.Attempt;
import java.time.Duration;
import java.util.List;

public final class RetentionPolicy {
    public record Decision(boolean wouldDelete, boolean actionAllowed, String reason) {}
    public Decision evaluate(Attempt a, RetentionConfig config) {
        if (a.important()) return preserve("manually marked important");
        if (a.excluded()) return preserve("excluded from deletion");
        if (a.endedAt() == null) return preserve("attempt is active");
        if (a.recordingPath() == null || a.recordingPath().isBlank()) return preserve("recording not verified");
        if ("NEEDS_REVIEW".equals(a.state())) return preserve("attempt state is unknown");

        boolean anyFilter = config.keepIfNether() || config.keepIfEnd() || config.keepIfDuration();
        if (!anyFilter) return preserve("no keep filters selected; safe default is preserve");
        if (config.keepIfNether() && a.netherReached()) return preserve("keep filter matched: entered the Nether");
        if (config.keepIfEnd() && a.endReached()) return preserve("keep filter matched: entered the End");
        if (config.keepIfDuration() && durationSeconds(a) >= config.minimumDurationSeconds())
            return preserve("keep filter matched: minimum attempt duration");

        String reason = "no selected keep filter matched";
        return new Decision(true, config.automaticDeletion() && !config.dryRun(),
                config.dryRun() ? "dry run: " + reason : !config.automaticDeletion() ? reason + "; automatic deletion disabled" : reason);
    }
    /** Evaluates the entire SeedQueue wall file as one batch, using every linked attempt. */
    public Decision evaluateWallBatch(List<Attempt> attempts, RetentionConfig config) {
        if (attempts == null || attempts.isEmpty()) return preserve("wall batch has no linked attempts; outcome is unknown");
        if (attempts.stream().anyMatch(Attempt::netherReached))
            return preserve("keep entire wall batch: at least one linked attempt reached the Nether");
        if (attempts.stream().anyMatch(a -> a.important() || a.excluded()))
            return preserve("wall batch contains an attempt marked important or excluded");
        if (attempts.stream().anyMatch(a -> a.endedAt() == null))
            return preserve("wall batch contains an active attempt");
        if (attempts.stream().anyMatch(a -> "NEEDS_REVIEW".equals(a.state()) || a.error() != null && !a.error().isBlank()))
            return preserve("wall batch contains an attempt with an unknown or unresolved result");
        if (attempts.stream().anyMatch(a -> !List.of("DELETE_PENDING", "IN_OVERWORLD", "QUARANTINED").contains(a.state())))
            return preserve("wall batch contains an attempt without a confirmed non-Nether result");

        if (config.keepIfEnd() && attempts.stream().anyMatch(Attempt::endReached))
            return preserve("keep filter matched: an attempt in this wall batch reached the End");
        if (config.keepIfDuration() && attempts.stream().anyMatch(a -> durationSeconds(a) >= config.minimumDurationSeconds()))
            return preserve("keep filter matched: an attempt in this wall batch met the minimum duration");
        if (!config.keepIfNether() && !config.keepIfEnd() && !config.keepIfDuration())
            return preserve("no keep filters selected; safe default is preserve");

        String reason = "all linked attempts ended with confirmed no Nether entry and no keep filter matched";
        return new Decision(true, config.automaticDeletion() && !config.dryRun(),
                config.dryRun() ? "dry run: " + reason : !config.automaticDeletion() ? reason + "; automatic deletion disabled" : reason);
    }
    /** Called only for a verified, finalized short wall clip with no linked attempts. */
    public Decision evaluateEmptyWallBatch(RetentionConfig config) {
        String reason = "short finalized wall clip had no linked attempts";
        return new Decision(true, config.automaticDeletion() && !config.dryRun(),
                config.dryRun() ? "dry run: " + reason
                        : !config.automaticDeletion() ? reason + "; automatic deletion disabled" : reason);
    }
    private static long durationSeconds(Attempt a) { return Math.max(0, Duration.between(a.startedAt(), a.endedAt()).getSeconds()); }
    private static Decision preserve(String reason) { return new Decision(false, false, reason); }
}

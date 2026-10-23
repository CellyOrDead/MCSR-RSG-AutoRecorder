package dev.autorecorder;

import dev.autorecorder.attempt.AttemptMachine;
import dev.autorecorder.model.Attempt;
import dev.autorecorder.retention.RetentionConfig;
import dev.autorecorder.retention.RetentionPolicy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class AttemptMachineTest {
    @Test void netherMilestoneSurvivesLaterStateAndIsRetained(){
        var machine=new AttemptMachine();var a=machine.begin(UUID.randomUUID(),"world-1");
        a=machine.dimension(a,"minecraft:the_nether");a=machine.finish(a,true);
        assertTrue(a.netherReached());assertEquals("KEEP",a.state());
        var d=new RetentionPolicy().evaluate(withVerifiedFile(a),RetentionConfig.safeDefaults());assertFalse(d.wouldDelete());
    }
    @Test void confirmedNetherRemainsKeepWhenMinecraftDisconnects(){
        var machine=new AttemptMachine();var a=machine.begin(UUID.randomUUID(),"world-1");
        a=machine.dimension(a,"minecraft:the_nether");a=machine.finish(a,false);
        assertTrue(a.netherReached());assertEquals("KEEP",a.state());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(a),RetentionConfig.safeDefaults()).wouldDelete());
    }
    @Test void unknownAndActiveAttemptsCannotBeDeleted(){
        var m=new AttemptMachine();var a=m.begin(UUID.randomUUID(),"w");var p=new RetentionPolicy();
        assertFalse(p.evaluate(a,RetentionConfig.safeDefaults()).wouldDelete());
        a=m.finish(a,false);assertEquals("NEEDS_REVIEW",a.state());assertFalse(p.evaluate(withVerifiedFile(a),RetentionConfig.safeDefaults()).wouldDelete());
    }
    @Test void durationCanBeConfiguredAsTheOnlyKeepFilter(){
        var m=new AttemptMachine();var start=m.begin(UUID.randomUUID(),"w");
        var longRun=withDuration(start,Instant.now().plusSeconds(601));
        var shortRun=withDuration(start,Instant.now().plusSeconds(599));
        var config=new RetentionConfig(false,false,true,600,false,true);var policy=new RetentionPolicy();
        assertFalse(policy.evaluate(longRun,config).wouldDelete());
        assertTrue(policy.evaluate(shortRun,config).wouldDelete());
        assertFalse(policy.evaluate(shortRun,config).actionAllowed());
    }
    @Test void automaticQuarantineRequiresExplicitEnableAndDryRunOff(){
        var m=new AttemptMachine();var a=m.begin(UUID.randomUUID(),"w");
        a=a.withState("IN_OVERWORLD",null);a=m.finish(a,true);a=withVerifiedFile(a);
        var policy=new RetentionPolicy();
        var safe=policy.evaluate(a,new RetentionConfig(true,false,false,300,false,true));
        var dryRun=policy.evaluate(a,new RetentionConfig(true,false,false,300,true,true));
        var enabled=policy.evaluate(a,new RetentionConfig(true,false,false,300,true,false));
        assertTrue(safe.wouldDelete());assertFalse(safe.actionAllowed());
        assertTrue(dryRun.wouldDelete());assertFalse(dryRun.actionAllowed());
        assertTrue(enabled.wouldDelete());assertTrue(enabled.actionAllowed());
    }
    @Test void endDimensionIsAnIndependentFilterAndNoFiltersPreserve(){
        var m=new AttemptMachine();var a=m.begin(UUID.randomUUID(),"w");a=m.dimension(a,"minecraft:the_end");a=m.finish(a,true);
        assertTrue(a.endReached());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(a),new RetentionConfig(false,true,false,300,false,true)).wouldDelete());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(a),new RetentionConfig(false,false,false,300,false,true)).wouldDelete());
    }
    @Test void endingWithoutDimensionEvidenceNeedsReview(){
        var machine=new AttemptMachine();var a=machine.begin(UUID.randomUUID(),"world");
        a=machine.finish(a,true);
        assertEquals("NEEDS_REVIEW",a.state());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(a),new RetentionConfig(false,false,true,60,false,true)).wouldDelete());
    }
    @Test void confirmedNoNetherAtCleanExitIsEligibleButUnknownExitIsNot(){
        var machine=new AttemptMachine();
        var attempt=machine.begin(UUID.randomUUID(),"world");
        var confirmed=machine.finish(attempt,true,false);
        var unknown=machine.finish(attempt,true,null);
        assertEquals("DELETE_PENDING",confirmed.state());
        assertEquals("NEEDS_REVIEW",unknown.state());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(confirmed),RetentionConfig.safeDefaults()).actionAllowed());
        assertFalse(new RetentionPolicy().evaluate(withVerifiedFile(unknown),RetentionConfig.safeDefaults()).wouldDelete());
    }
    private static Attempt withVerifiedFile(Attempt a){return new Attempt(a.id(),a.sessionId(),a.worldId(),a.startedAt(),a.endedAt(),a.netherReached(),a.endReached(),a.netherAt(),a.state(),"C:/managed/test.mkv",a.retentionDecision(),a.important(),a.excluded(),a.error());}
    private static Attempt withDuration(Attempt a,Instant ended){return new Attempt(a.id(),a.sessionId(),a.worldId(),a.startedAt(),ended,false,false,null,"FINISHED","C:/managed/test.mkv","PENDING",false,false,null);}
}

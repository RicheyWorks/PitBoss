package io.github.richeyworks.pitboss;

import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.superbeefsort.external.SpillSerializer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floor against the oracle: fleet convergence through the conductor, re-bootstrap as a
 * cold start that converges again, and the promotion runbook ending in a writable store that
 * equals the primary's truth. Seeded; awaits bounded (the wire and tail threads are real).
 */
class PitBossTest {

    private static final long AWAIT = 10_000;

    private static SmokeHouseOptions<Long, String> opts() {
        return SmokeHouseOptions.of(SpillSerializer.forLongs(), SpillSerializer.forStrings())
                .segmentBytes(2048)
                .indexTier(SmokeHouseOptions.IndexTier.STATIC);
    }

    private static void churn(SmokeHouse<Long, String> primary, TreeMap<Long, String> oracle,
                              Random rnd, int ops) throws IOException {
        for (int i = 0; i < ops; i++) {
            long key = rnd.nextInt(120);
            if (rnd.nextInt(6) == 0) {
                primary.delete(key);
                oracle.remove(key);
            } else {
                String v = "v" + key + ":" + i;
                primary.put(key, v);
                oracle.put(key, v);
            }
        }
    }

    private static TreeMap<Long, String> scan(SmokeHouse<Long, String> store) throws IOException {
        TreeMap<Long, String> out = new TreeMap<>();
        if (store.size() > 0) {
            store.range(store.firstKey(), store.lastKey(), out::put);
        }
        return out;
    }

    @Test
    void theFloorConvergesAndTickReportsIt(@TempDir Path primaryDir, @TempDir Path a,
                                           @TempDir Path b) throws IOException {
        Random rnd = new Random(42);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (SmokeHouse<Long, String> primary = SmokeHouse.open(primaryDir, opts());
             PitBoss<Long, String> boss = PitBoss.over(primary, opts(), true)) {
            churn(primary, oracle, rnd, 300);
            boss.addReplica("a", a);
            boss.addReplica("b", b);
            churn(primary, oracle, rnd, 300);

            long target = primary.tailSequence();
            assertTrue(boss.replica("a").awaitCaughtUp(target, AWAIT));
            assertTrue(boss.replica("b").awaitCaughtUp(target, AWAIT));

            PitBoss.FleetReport report = boss.tick();
            assertEquals(2, report.replicas().size());
            for (PitBoss.ReplicaStatus s : report.replicas()) {
                assertEquals(0, s.lag(), s.name() + " caught up means zero lag");
                assertFalse(s.gapped());
                assertFalse(s.rebootstrapped());
            }
            assertEquals(oracle, scan(boss.replica("a").store()));
            assertEquals(oracle, scan(boss.replica("b").store()));
        }
    }

    @Test
    void rebootstrapIsAColdStartThatConvergesAgain(@TempDir Path primaryDir, @TempDir Path a)
            throws IOException {
        Random rnd = new Random(7);
        TreeMap<Long, String> oracle = new TreeMap<>();
        try (SmokeHouse<Long, String> primary = SmokeHouse.open(primaryDir, opts());
             PitBoss<Long, String> boss = PitBoss.over(primary, opts(), true)) {
            churn(primary, oracle, rnd, 250);
            boss.addReplica("a", a);
            assertTrue(boss.replica("a").awaitCaughtUp(primary.tailSequence(), AWAIT));

            boss.rebootstrap("a");                             // wipe + fresh shipped backup
            churn(primary, oracle, rnd, 250);
            assertTrue(boss.replica("a").awaitCaughtUp(primary.tailSequence(), AWAIT),
                    "the reborn replica must converge");
            assertEquals(oracle, scan(boss.replica("a").store()));
        }
    }

    @Test
    void thePromotionRunbookEndsInAWritablePrimary(@TempDir Path primaryDir, @TempDir Path a)
            throws IOException {
        Random rnd = new Random(11);
        TreeMap<Long, String> oracle = new TreeMap<>();
        SmokeHouse<Long, String> promoted;
        try (SmokeHouse<Long, String> primary = SmokeHouse.open(primaryDir, opts())) {
            try (PitBoss<Long, String> boss = PitBoss.over(primary, opts(), true)) {
                churn(primary, oracle, rnd, 400);
                boss.addReplica("a", a);
                assertTrue(boss.replica("a").awaitCaughtUp(primary.tailSequence(), AWAIT));
                promoted = boss.promote("a");                  // closes the floor, reopens a
            }
        }
        try (promoted) {
            assertEquals(oracle, scan(promoted), "promotion preserves the truth");
            promoted.put(999L, "written-after-promotion");     // and it takes writes
            assertEquals("written-after-promotion", promoted.get(999L));
        }
    }
}

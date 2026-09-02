package io.github.richeyworks.pitboss;

import io.github.richeyworks.smokehouse.Replica;
import io.github.richeyworks.smokehouse.ReplicationServer;
import io.github.richeyworks.smokehouse.SmokeHouse;
import io.github.richeyworks.smokehouse.SmokeHouseOptions;
import io.github.richeyworks.smokehouse.TailListener;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * PitBoss — engine seven of the ecosystem: the fleet conductor, the one who runs the
 * smokehouse floor. One primary, N read replicas, one caller-cadenced {@link #tick()} that
 * turns Phase 8's manual operations into policy: watch every replica's lag, re-bootstrap the
 * gapped ones (a cold start is always acceptable, a wrong replica never is), and run the
 * <b>promotion runbook</b> as one audited operation.
 *
 * <p><b>What PitBoss deliberately is not:</b> a consensus system. It never decides to promote
 * — a human calls {@link #promote}; PitBoss makes the doing atomic and on the record. No
 * automatic failover, no write forwarding, no split-brain prevention: the Phase 8 non-goals
 * transfer verbatim, one floor up.</p>
 *
 * <p>Caller-cadenced like every control loop in the ring: no clock, no threads of PitBoss's
 * own (the replication threads belong to SmokeHouse). Loopback-only, as tradition demands.</p>
 */
public final class PitBoss<K, V> implements Closeable {

    /**
     * One replica's vitals at a {@link #tick()}, plus what the policy did about them.
     * {@code lag} is measured from the conductor's seat — the primary's committed sequence
     * minus what the replica has applied — not from the replica's own last frame: a replica
     * whose feed is held back has not yet heard how far behind it is, and reported zero
     * (found 2026-09-02 the first time the feed was actually held back).
     */
    public record ReplicaStatus(String name, long lag, boolean gapped, boolean rebootstrapped) { }

    /** One tick's verdict over the whole floor. */
    public record FleetReport(long primarySequence, List<ReplicaStatus> replicas) { }

    private final SmokeHouse<K, V> primary;
    private final SmokeHouseOptions<K, V> opts;
    private final ReplicationServer<K, V> server;
    private final Map<String, Path> dirs = new LinkedHashMap<>();
    private final Map<String, Replica<K, V>> fleet = new LinkedHashMap<>();
    private final boolean autoRebootstrap;
    private boolean closed;

    private PitBoss(SmokeHouse<K, V> primary, SmokeHouseOptions<K, V> opts,
                    ReplicationServer<K, V> server, boolean autoRebootstrap) {
        this.primary = primary;
        this.opts = opts;
        this.server = server;
        this.autoRebootstrap = autoRebootstrap;
    }

    /**
     * Conduct {@code primary}: start serving replication and manage the fleet. The primary
     * belongs to the caller (PitBoss never closes it); auto-re-bootstrap of gapped replicas
     * is on by default — pass {@code false} to only report them.
     */
    public static <K, V> PitBoss<K, V> over(SmokeHouse<K, V> primary,
                                            SmokeHouseOptions<K, V> opts,
                                            boolean autoRebootstrap) throws IOException {
        return over(primary, opts, autoRebootstrap, UnaryOperator.identity());
    }

    /**
     * As {@link #over(SmokeHouse, SmokeHouseOptions, boolean)}, with a wrapper applied to
     * every replica's feed listener before it is subscribed — SmokeHouse's feed seam, passed
     * through. A {@code Sizzle.slow} here holds the whole fleet behind the primary for real.
     */
    public static <K, V> PitBoss<K, V> over(SmokeHouse<K, V> primary,
                                            SmokeHouseOptions<K, V> opts,
                                            boolean autoRebootstrap,
                                            UnaryOperator<TailListener<K, V>> feed) throws IOException {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(opts, "opts");
        Objects.requireNonNull(feed, "feed");
        return new PitBoss<>(primary, opts, ReplicationServer.serve(primary, opts, feed),
                autoRebootstrap);
    }

    /** Bootstrap a named replica into {@code dir} and add it to the floor. */
    public synchronized Replica<K, V> addReplica(String name, Path dir) throws IOException {
        requireOpen();
        Objects.requireNonNull(name, "name");
        if (fleet.containsKey(name)) {
            throw new IllegalArgumentException("duplicate replica name: " + name);
        }
        Replica<K, V> replica = Replica.connect(dir, opts, server.port());
        dirs.put(name, dir);
        fleet.put(name, replica);
        return replica;
    }

    /** The named replica (reads ride its own store). */
    public synchronized Replica<K, V> replica(String name) {
        Replica<K, V> r = fleet.get(name);
        if (r == null) {
            throw new IllegalArgumentException("no replica named '" + name
                    + "'; on the floor: " + fleet.keySet());
        }
        return r;
    }

    /**
     * One policy pass over the floor: read every replica's vitals; gapped replicas are
     * re-bootstrapped when the policy allows, otherwise reported. Caller-cadenced — call it
     * from your own loop at your own rhythm.
     */
    public synchronized FleetReport tick() throws IOException {
        requireOpen();
        List<ReplicaStatus> statuses = new ArrayList<>(fleet.size());
        for (String name : List.copyOf(fleet.keySet())) {
            Replica<K, V> r = fleet.get(name);
            boolean gapped = r.gapped();
            boolean rebooted = false;
            if (gapped && autoRebootstrap) {
                rebootstrap(name);
                r = fleet.get(name);
                rebooted = true;
                gapped = r.gapped();
            }
            statuses.add(new ReplicaStatus(name, lagOf(r), gapped, rebooted));
        }
        return new FleetReport(primary.tailSequence(), statuses);
    }

    /** The replica's lag as the conductor sees it: committed on the primary, not yet applied here. */
    private long lagOf(Replica<K, V> r) {
        return Math.max(0, (primary.tailSequence() - 1) - r.appliedSequence());
    }

    /**
     * Cold-start the named replica: close it, wipe its directory, reconnect from a fresh
     * shipped backup. The recovery move for a gapped (or suspect) replica — always
     * acceptable, never wrong.
     */
    public synchronized void rebootstrap(String name) throws IOException {
        requireOpen();
        Replica<K, V> old = replica(name);
        Path dir = dirs.get(name);
        old.close();
        wipe(dir);
        fleet.put(name, Replica.connect(dir, opts, server.port()));
    }

    /**
     * The promotion runbook, one audited operation: stop serving replication, disconnect and
     * close every replica, close out the floor, and reopen the named replica's directory as
     * a plain primary store — which is all promotion is, by Phase 8's design. The old
     * primary is NOT closed (it belongs to the caller — fence it yourself before promoting;
     * split-brain prevention is the operator's problem, stated loudly).
     *
     * @return the promoted store, open for writes
     */
    public synchronized SmokeHouse<K, V> promote(String name) throws IOException {
        requireOpen();
        Path dir = dirs.get(name);
        if (dir == null) {
            throw new IllegalArgumentException("no replica named '" + name
                    + "'; on the floor: " + fleet.keySet());
        }
        close();                                               // server + every replica
        return SmokeHouse.open(dir, opts);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("this PitBoss has left the floor (closed)");
        }
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.equals(dir)) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** Stop serving and close every replica. The primary stays open — it's the caller's. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        server.close();
        for (Replica<K, V> r : fleet.values()) {
            try {
                r.close();
            } catch (IOException ignored) {
                // floor teardown; each replica's dir remains a valid store either way
            }
        }
        fleet.clear();
    }
}

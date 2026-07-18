# PitBoss

[![CI](https://github.com/RicheyWorks/PitBoss/actions/workflows/ci.yml/badge.svg)](https://github.com/RicheyWorks/PitBoss/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net/)

Engine seven of the ecosystem: the **fleet conductor** — the one who runs the smokehouse
floor. One primary, N read replicas, one caller-cadenced `tick()`: watch every replica's
lag, re-bootstrap the gapped ones (a cold start is always acceptable, a wrong replica never
is), and run the **promotion runbook** as one audited operation.

```java
try (var primary = SmokeHouse.open(dir, opts);
     var boss = PitBoss.over(primary, opts, /* autoRebootstrap */ true)) {
    boss.addReplica("a", dirA);
    boss.addReplica("b", dirB);
    FleetReport report = boss.tick();      // lag / gapped / rebootstrapped, per replica
    SmokeHouse<K,V> promoted = boss.promote("a");   // a human decided; PitBoss does it right
}
```

PitBoss never *decides* to promote — no consensus, no automatic failover, no write
forwarding; the Phase 8 non-goals transfer verbatim, one floor up. Fencing the old primary
before promoting is the operator's job, stated loudly.

## Design notes

- **Never decides, only does.** Promotion is human-triggered; PitBoss makes the runbook
  atomic and on the record. Consensus, auto-failover, and write forwarding are non-goals
  inherited verbatim from Phase 8 — adding any of them takes an ADR, not a patch.
- **Cold starts are doctrine.** `rebootstrap` is close + wipe + reconnect from a fresh
  shipped backup — a gapped replica is never patched in place, because a cold start is
  always acceptable and a wrong replica never is.
- **Caller-cadenced.** `tick()` runs on your thread at your rhythm; PitBoss owns no threads
  (replication's threads belong to SmokeHouse). Each tick returns a `FleetReport` — lag,
  gapped, rebootstrapped, per replica — so the floor's state is always one record.
- **The primary belongs to the caller.** PitBoss never closes it, even during promotion;
  fencing the old primary is the operator's job, stated loudly.

## The ecosystem

Eleven engines, one organism — each in its own repo, composed by nested Gradle
composite builds:

| Engine | Role |
|---|---|
| [CSRBT](https://github.com/RicheyWorks/CSRBT) | the adaptive ordered index — orders the world |
| [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) | the intake tract — profiles, sorts, feeds in O(n) |
| [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) | the log-structured store — durability, tail, watchers, replicas |
| [Carver](https://github.com/RicheyWorks/Carver) | the read planner — decides how to read |
| [Renderer](https://github.com/RicheyWorks/Renderer) | the materialized-view engine — folds the tail into live aggregates |
| [Brine](https://github.com/RicheyWorks/Brine) | the adaptive cache — eviction policy evolved per workload |
| **PitBoss** (this repo) | the fleet conductor — lag watch, re-bootstrap, the promotion runbook |
| [DryAge](https://github.com/RicheyWorks/DryAge) | the time-travel engine — as-of reads over preserved history |
| [Twine](https://github.com/RicheyWorks/Twine) | crash-atomic multi-key batches — journaled commit, idempotent replay |
| [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) | the wire — a loopback protocol face for the store |
| [Jerky](https://github.com/RicheyWorks/Jerky) | cold storage — compressed, CRC-verified backup archives |
| [WholeHog](https://github.com/RicheyWorks/WholeHog) | the integration organism — all of them, at once |

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.

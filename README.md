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

## The ecosystem

Engines 1–6: [CSRBT](https://github.com/RicheyWorks/CSRBT) (index) · [SuperBeefSort](https://github.com/RicheyWorks/SuperBeefSort) (intake) · [SmokeHouse](https://github.com/RicheyWorks/SmokeHouse) (store) · [Carver](https://github.com/RicheyWorks/Carver) (read planner) · [Renderer](https://github.com/RicheyWorks/Renderer) (materialized views) · [Brine](https://github.com/RicheyWorks/Brine) (adaptive cache).
Engines 7–11: [PitBoss](https://github.com/RicheyWorks/PitBoss) (fleet conductor) · [DryAge](https://github.com/RicheyWorks/DryAge) (time travel) · [Twine](https://github.com/RicheyWorks/Twine) (atomic batches) · [SmokeSignal](https://github.com/RicheyWorks/SmokeSignal) (the wire) · [Jerky](https://github.com/RicheyWorks/Jerky) (cold archives).

## Build

```bash
# Requires ../SmokeHouse, ../SuperBeefSort, ../CSRBT cloned as siblings (nested composite build)
./gradlew build
```

Java 17+, Gradle 9.5.1 (bundled wrapper). Seeded oracle tests in the house style. MIT license.

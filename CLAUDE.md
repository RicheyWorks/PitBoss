# PitBoss — working notes for agents

Engine 7: the replica-fleet conductor over SmokeHouse Phase 8 replication. One class
(`PitBoss`): `addReplica`/`tick`/`rebootstrap`/`promote` over public
`ReplicationServer`/`Replica` surfaces only.

## Invariants (do not break)
- **Never decide, only do.** Promotion is human-triggered; PitBoss makes it atomic and on
  the record. No consensus, no auto-failover — adding either needs its own ADR, not a patch.
- **Cold starts are always acceptable.** rebootstrap = close + wipe + reconnect; never try
  to patch a gapped replica in place.
- **The primary belongs to the caller** — PitBoss never closes it, even in `promote`.
- Caller-cadenced `tick()`; no threads of PitBoss's own. Oracle tests in `PitBossTest`.

## Git is host-side
Same as the siblings: agent sandboxes cannot write `.git`. Run all git commands from a host
terminal (PowerShell). Stale `.git/index.lock` fix: `Remove-Item .git\index.lock -Force`.

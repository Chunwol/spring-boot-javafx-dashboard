# SCMate RX2 integration

This document records the verified command contract for the SCMate RX2 client
used by the classroom agent. It intentionally contains no credentials.

## Supported runtime

- SCMateClient product family: `A`
- Shield runtime: `12.95`
- The agent must reject an unknown product family instead of trying commands
  from another family.
- `ShdCmd /ResMode` and `frcmd.exe` are not part of the product A control path.
- `ShdCmd /protect enable|disable` controls the recovery engine itself. It does
  not switch between operator mode and scheduled recovery mode.

## Recovery points

| API value | Snapshot name | Schedule/restore name | Schedule task |
| --- | --- | --- | --- |
| `BASELINE` | `SCMATE_BASELINE` | `Win:SCMATE_BASELINE` | `SCMATEBASE_TASK` |
| `SNAP1` | `SCMATE_SNAP1` | `Win:SCMATE_SNAP1` | `SCMATE_SNAP1_TASK` |
| `SNAP2` | `SCMATE_SNAP2` | `Win:SCMATE_SNAP2` | `SCMATE_SNAP2_TASK` |

The normal UI default is `BASELINE`. The other points remain selectable and
must be preserved when reading or changing an existing policy.

## Product A commands

Authentication arguments are appended by the agent and must never be logged.

| Operation | Command arguments | Reboots immediately |
| --- | --- | --- |
| Operator mode | `/DelTask /type reset` | No |
| Every-boot recovery | `DelTask`, then `/AddResetTask /schtype restart /name <TASK> /n <POINT>` | No |
| Save recovery point | `/Snapshot /n <SNAPSHOT_NAME>` | No |
| Restore now | `/Restore /n <SCHEDULE_NAME>` | Yes |
| Read points | `/List` | No |
| Read schedule | `/ListTask` | No |

Changing to operator mode removes the reset schedule but does not delete a
recovery point. Changing to every-boot recovery must use the user-selected
point. Saving and restoring must verify the selected point, not assume the
baseline.

Product A can retain multiple recovery points with the same display name. When
reading `/List`, the agent selects the newest vendor-reported creation time.
Mode changes are successful only after `/ListTask` confirms the requested reset
schedule. The agent does not restart SCMateClient after changing the schedule.

## Display states

| Raw | Schedule | Point | Display meaning |
| ---: | --- | --- | --- |
| 0 | `RESTART` | `BASELINE` | Every boot |
| 1 | `DAILY_FIRST_BOOT` | `BASELINE` | First boot each day |
| 2 | `UNKNOWN` | `UNKNOWN` | Reserved/unknown |
| 3 | `MANUAL` | `UNKNOWN` | Operator mode |
| 4 | `WEEKLY` | `BASELINE` | Selected weekday |
| 5 | `RESTART` | `SNAP1` | Every boot, point 1 |
| 6 | `RESTART` | `SNAP2` | Every boot, point 2 |
| 7 | `DAILY_FIRST_BOOT` | `SNAP1` | First boot each day, point 1 |
| 8 | `DAILY_FIRST_BOOT` | `SNAP2` | First boot each day, point 2 |
| 9 | `UNKNOWN` | `UNKNOWN` | Unavailable; requires cause classification |
| 10 | `UNKNOWN` | `UNKNOWN` | License expired |
| 11 | `WEEKLY` | `SNAP1` | Selected weekday, point 1 |
| 12 | `WEEKLY` | `SNAP2` | Selected weekday, point 2 |

Raw state `9` alone does not prove that the module is absent. It can also mean
that protection was disabled or status retrieval failed. Installation and
re-enable actions require independent file, API, and process evidence.

## Verification gates

1. Validate the executable path, supported hash, and product family.
2. Read `/ListTask` and `/List` before a mutating command.
3. Run exactly the command sequence for product A and the selected point.
4. Read `/ListTask`, `/List`, and the SCMateClient display state afterward.
5. For a snapshot, accept the result only when the selected point has an actual
   vendor-reported creation time after the request.
6. For a reboot-dependent workflow, require the Windows system boot time to
   change. An agent process/session restart is not reboot proof.
7. Complete the full round trip on one designated PC before a fleet rollout.

## Prohibited shortcuts

- Do not infer operator mode from the low-level protect enabled/disabled flag.
- Do not run `/protect disable` to enter operator mode.
- Do not replace an existing `SNAP1`, `SNAP2`, daily, or weekly policy with the
  baseline restart policy without an explicit user selection.
- Do not store the backend command completion time as a recovery-point creation
  time.
- Do not report a fleet rollout as successful from queue acceptance alone.

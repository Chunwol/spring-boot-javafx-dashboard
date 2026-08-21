# AX3000Q WAN WOL rollout

## Scope

The classroom PCs are attached to the routers' WAN-side public `/24` networks.
Stock AX3000Q firmware sends WOL frames through `br-lan`, so it cannot wake
those PCs. The rollout firmware is based on the currently installed ipTIME
`15.25.8` image and changes only the WOL packet socket and source-MAC lookups in
`plugin_network.so` from `br-lan` to the actual WAN kernel interface `lan0`.
`wan1` is only the management API's logical interface name on these routers.

The stock configuration, kernel, model ID, firmware version, extracted file
modes, symlinks, and file contents are otherwise preserved. The image has now
completed physical upgrade, reboot, and settings verification on all nine
AX3000Q routers. WAN WOL was physically verified on eight routers; the 214
verification was explicitly limited to the router so no 214 PC was powered on.

## Upgrade behavior

- A normal manual ipTIME firmware upgrade preserves the saved configuration.
- Firmware below `14.900` triggers a factory-reset path. This image is the same
  `15.25.8` version as the installed firmware and does not enter that path.
- Firmware 15.25.8 has no boot-time or cron-based unattended updater. Its
  administrator UI's "automatic upgrade" is a manually initiated online
  upgrade action, not a persistent schedule.
- The only unattended remote installation path is CWMP. It is disabled and has
  no ACS URL on all nine routers. The rollout script refuses to upload when
  that is no longer true and verifies the state again after restart.
- Do not run the online upgrade action after this rollout. A later official
  firmware installation will overwrite the WAN WOL patch.
- Keep the official image and each router's configuration backup available for
  physical recovery.

## Fixed image identities

| File | Purpose | SHA-256 |
| --- | --- | --- |
| `ax3000q_ml_15_258-wanwol-VERIFIED.bin` | Physically verified WAN WOL rollout image | `63C9F15177E39787AFAF51DFD116DB6A426C996C2736F5A242C9C760BE0966B0` |
| `ax3000q_ml_15_258-OFFICIAL-ROLLBACK.bin` | Official 15.25.8 rollback | `BB558444A09FF1264AEA7BA683CD68E4ECC77DB05704809A2D0774DE5050A129` |

The rollout script validates the SHA-256, ipTIME outer CRC, model
`ax3000q`, version `15_258`, payload size, router maximum image size, installed
firmware snapshot, WAN address, WAN kernel interface, and the target router's
unique WAN MAC before it can upload anything.

Routers without an EasyMesh configuration expose `system/info.wireless`
instead of `system/info.easymesh`. The rollout script snapshots and compares
the per-band enabled state and SSID from either representation before and after
the restart; absence of both representations remains a hard stop.

## Safety gates

Run one router at a time. The script stops before upload when any gate fails:

1. Firmware identity or checksum mismatch.
2. Configuration backup is not a valid `GEN_V2` backup.
3. The installed firmware snapshot or target router WAN MAC does not match.
4. The router reports one or more connected LAN stations, including the second
   check immediately before upload.
5. `-ConfirmTarget` does not exactly match `-TargetLabel`.
6. The verification PC is not in the router WAN `/24` or answers any of three
   offline checks.
7. A verification MAC address or ping address is missing.
8. A 214 target is selected without the explicit `-Allow214` maintenance
   override. When 214 PCs must not be powered on, add `-SkipWolVerification`;
   this exception is rejected for every other target and still performs router
   backup, firmware upload, restart, and settings comparison.

The first target was `217-1-warehouse` at `223.194.167.252:8080`, WAN MAC
`B0:38:6C:95:69:09`. Its WOL verification target was student PC `217-01`,
`223.194.167.201`, MAC `84:A9:3E:6A:89:B6`. The 214 router was later selected
with `-Allow214 -SkipWolVerification`; this preserved the same firmware and
settings checks while preventing any 214 PC power action.

The routers expose the management API over HTTP only. Run this procedure from
the trusted campus/VPN management path. The password is protected from command
history by `SecureString`, but HTTP does not encrypt it on the network.

## Dry run

Provide the router password as a secure string. Do not put it in a command file
or commit it to the repository. Run the commands below from the
`dist/router-wanwol` directory.

```powershell
$password = Read-Host 'Router password' -AsSecureString
& .\router-wanwol-rollout.ps1 `
  -TargetLabel '217-1-warehouse' `
  -BaseUri 'http://223.194.167.252:8080' `
  -RouterUser 'dy' `
  -RouterPassword $password `
  -FirmwarePath '.\ax3000q_ml_15_258-wanwol-VERIFIED.bin' `
  -ExpectedSha256 '63C9F15177E39787AFAF51DFD116DB6A426C996C2736F5A242C9C760BE0966B0' `
  -ExpectedWanMac 'B0:38:6C:95:69:09'
```

The dry run logs in, reads system state, verifies zero connected stations,
creates another configuration backup, and logs out. It never calls the firmware
prepare or upload endpoints.

## Verified rollout example

Use the pre-checked offline `217-01` student PC. The script refuses to continue
when the test PC answers any of three pings immediately before the upload.

```powershell
$password = Read-Host 'Router password' -AsSecureString
& .\router-wanwol-rollout.ps1 `
  -TargetLabel '217-1-warehouse' `
  -ConfirmTarget '217-1-warehouse' `
  -BaseUri 'http://223.194.167.252:8080' `
  -RouterUser 'dy' `
  -RouterPassword $password `
  -FirmwarePath '.\ax3000q_ml_15_258-wanwol-VERIFIED.bin' `
  -ExpectedSha256 '63C9F15177E39787AFAF51DFD116DB6A426C996C2736F5A242C9C760BE0966B0' `
  -ExpectedWanMac 'B0:38:6C:95:69:09' `
  -TestMac '84-A9-3E-6A-89-B6' `
  -TestPingAddress '223.194.167.201' `
  -Apply
```

After upload the router normally becomes unavailable while writing and
rebooting. The script waits up to five minutes, logs in again, compares the
critical operating settings captured by the script, then sends WOL and waits up
to three minutes for the selected PC. A transient WAN link speed is
intentionally excluded from the equality check; successful login through the
WAN address and a restored link verify remote access after restart. The test PC
must answer three consecutive pings before WOL is accepted as successful. A
temporary WOL registration made by the script is removed. Full configuration
recovery is provided by the timestamped `GEN_V2` backup rather than the smaller
semantic comparison snapshot.

## Stop and recovery rules

- Do not proceed to the next router unless the current router passes settings
  comparison and the WAN-side PC wake test.
- If the router returns but one PC does not wake, first test a second uniquely
  registered offline PC from that classroom. `314-01` did not wake while
  `314-21` verified the same router patch. Roll back only when a second suitable
  target also fails or the router itself is unhealthy.
- If settings differ, restore that router's matching `.cfg` backup from
  `C:\Users\chunwol\PCAgent-Router-Backups`.
- If the router does not return within five minutes, stop. Use the official
  ipTIME recovery procedure and the official rollback image at the router.
- Never use `-AllowConnectedStations` during the classroom rollout. It exists
  only for an attended maintenance exception.

```powershell
$password = Read-Host 'Router password' -AsSecureString
& .\router-wanwol-rollout.ps1 `
  -TargetLabel '217-1-warehouse' `
  -ConfirmTarget '217-1-warehouse' `
  -BaseUri 'http://223.194.167.252:8080' `
  -RouterUser 'dy' `
  -RouterPassword $password `
  -FirmwarePath '.\ax3000q_ml_15_258-OFFICIAL-ROLLBACK.bin' `
  -ExpectedSha256 'BB558444A09FF1264AEA7BA683CD68E4ECC77DB05704809A2D0774DE5050A129' `
  -ExpectedWanMac 'B0:38:6C:95:69:09' `
  -Apply `
  -Rollback
```

## Router status

Re-check station count and the selected PC's live status immediately before
every future upload because both can change.

| Router | Address | Verification PC | Status |
| --- | --- | --- | --- |
| `217-1-warehouse` | `223.194.167.252:8080` | `217-01`, `223.194.167.201`, `84:A9:3E:6A:89:B6` | Verified |
| `212` | `223.194.166.245:8080` | `212-02`, `223.194.166.106`, `80:E8:2C:FC:D2:5A` | Verified |
| `support` | `223.194.166.249:8080` | `210-01`, `223.194.166.21`, `64:4E:D7:20:8A:FE` | Verified |
| `218` | `223.194.168.251:8080` | `218-01`, `223.194.168.21`, `7C:4D:8F:09:EA:BD` | Verified |
| `314-1-warehouse` | `223.194.169.250:8080` | 315호 대상 PC | Address move configured; live WOL verification pending |
| `214-1-warehouse` | `223.194.167.253:8080` | WOL intentionally skipped | Verified restart/settings; no PC power action |
| `102` | `172.16.204.151:8080` | `102-01`, `172.16.204.21`, `7C:57:58:35:EF:D3` | Verified |
| `109-1` | `172.16.204.150:8080` | `109-1-01`, `172.16.204.61`, `BC:0F:F3:C3:5D:F2` | Verified |
| `6-112` | `172.16.13.160:8080` | `112_T`, `172.16.13.13`, `6C:02:E0:6D:42:E3` | Verified without EasyMesh |

The order is operational, not a bulk action. Each invocation handles exactly
one router and creates a new timestamped backup plus a success or failure result
record. The two routers in each `/24` are intentional primary/secondary WOL
gateways; both are patched only after the first gateway in that subnet succeeds,
preserving fallback when one router is unreachable.

## Final evidence

`VERIFICATION.json` is generated only after all nine success records, their
matching `GEN_V2` backups, backup SHA-256 values, and the final read-only router
audit pass validation. It records eight physical WAN WOL checks and the explicit
214 WOL exception. Configuration backup files are intentionally kept outside
the distributable because they contain device-specific settings.

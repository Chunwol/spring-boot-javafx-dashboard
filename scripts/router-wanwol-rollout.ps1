[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TargetLabel,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^https?://')]
    [string]$BaseUri,

    [string]$RouterUser = $env:IPTIME_USERNAME,

    [Security.SecureString]$RouterPassword,

    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$FirmwarePath,

    [string]$ExpectedSha256,

    [ValidatePattern('(?i)^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$')]
    [string]$ExpectedWanMac,

    [string]$BackupRoot = (Join-Path $env:USERPROFILE 'PCAgent-Router-Backups'),

    [switch]$Apply,

    [switch]$Rollback,

    [switch]$ResumePreparedUpgrade,

    [string]$ConfirmTarget,

    [switch]$TakeOverSession,

    [switch]$AllowConnectedStations,

    [switch]$Allow214,

    [switch]$SkipWolVerification,

    [ValidatePattern('(?i)^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$')]
    [string]$TestMac,

    [ValidatePattern('^\d{1,3}(\.\d{1,3}){3}$')]
    [string]$TestPingAddress
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http

function Get-PlainTextPassword {
    param([Security.SecureString]$Value)

    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Get-IptimeCrc32 {
    param([byte[]]$Bytes)

    [uint32]$crc = 4294967295
    foreach ($value in $Bytes) {
        $crc = [uint32]($crc -bxor [uint32]$value)
        for ($bit = 0; $bit -lt 8; $bit++) {
            if (($crc -band 1) -ne 0) {
                $crc = [uint32](($crc -shr 1) -bxor [uint32]3988292384)
            } else {
                $crc = [uint32]($crc -shr 1)
            }
        }
    }
    return $crc
}

function Get-IptimeFirmwareMetadata {
    param([string]$Path)

    $resolved = (Resolve-Path -LiteralPath $Path).Path
    [byte[]]$bytes = [IO.File]::ReadAllBytes($resolved)
    if ($bytes.Length -lt 56) {
        throw 'The firmware file is smaller than the ipTIME header.'
    }

    $model = [Text.Encoding]::ASCII.GetString($bytes, 0, 8).Trim([char]0)
    $version = [Text.Encoding]::ASCII.GetString($bytes, 8, 8).Trim([char]0)
    $declaredSize = [BitConverter]::ToUInt32($bytes, 48)
    $storedCrc = [BitConverter]::ToUInt32($bytes, 52)
    $crcInput = [byte[]]$bytes.Clone()
    $crcInput[52] = 0
    $crcInput[53] = 0
    $crcInput[54] = 0
    $crcInput[55] = 0
    $computedCrc = Get-IptimeCrc32 -Bytes $crcInput
    $sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToUpperInvariant()

    [pscustomobject]@{
        Path = $resolved
        Length = $bytes.Length
        Model = $model
        Version = $version
        DeclaredPayloadSize = [uint64]$declaredSize
        StoredCrc = ('{0:X8}' -f $storedCrc)
        ComputedCrc = ('{0:X8}' -f $computedCrc)
        CrcValid = $storedCrc -eq $computedCrc
        Sha256 = $sha256
    }
}

function New-RouterClient {
    param([string]$Origin)

    $cookies = [Net.CookieContainer]::new()
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.CookieContainer = $cookies
    $handler.UseCookies = $true
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [Threading.Timeout]::InfiniteTimeSpan
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('ClassroomDashboard-RouterRollout/1.0')
    $client.DefaultRequestHeaders.TryAddWithoutValidation('Accept', 'application/json, text/plain, */*') | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation('Cache-Control', 'no-store') | Out-Null
    $client.DefaultRequestHeaders.TryAddWithoutValidation('Origin', $Origin) | Out-Null
    $client.DefaultRequestHeaders.Referrer = [Uri]($Origin + '/ui/')
    return $client
}

function Invoke-RouterRpc {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [string]$Method,
        [AllowNull()][object]$Params = $null,
        [switch]$Envelope,
        [int]$TimeoutSeconds = 25
    )

    $payload = [ordered]@{ method = $Method }
    if ($null -ne $Params) {
        $payload.params = $Params
    }
    $json = $payload | ConvertTo-Json -Depth 12 -Compress
    $content = [Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
    $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    try {
        $response = $Client.PostAsync(
            $Origin + '/cgi/service.cgi', $content, $timeout.Token).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Router RPC failed: $Method (HTTP $([int]$response.StatusCode))."
        }
        $decoded = $body | ConvertFrom-Json
        $errorProperty = $decoded.PSObject.Properties['error']
        if ($null -ne $errorProperty -and $null -ne $errorProperty.Value) {
            $codeProperty = $errorProperty.Value.PSObject.Properties['code']
            $code = if ($null -ne $codeProperty) { $codeProperty.Value } else { 'unknown' }
            throw "Router RPC failed: $Method (code $code)."
        }
        $resultProperty = $decoded.PSObject.Properties['result']
        if ($null -eq $resultProperty) {
            throw "Router RPC returned no result: $Method."
        }
        if ($Envelope) {
            return [pscustomobject]@{ Value = $resultProperty.Value }
        }
        return $resultProperty.Value
    } finally {
        $timeout.Dispose()
        $content.Dispose()
    }
}

function Connect-Router {
    param(
        [string]$Origin,
        [string]$Username,
        [string]$Password,
        [bool]$TakeOver
    )

    $client = New-RouterClient -Origin $Origin
    try {
        $timeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(25))
        try {
            $landing = $client.GetAsync($Origin + '/ui/', $timeout.Token).GetAwaiter().GetResult()
        } finally {
            $timeout.Dispose()
        }
        if (-not $landing.IsSuccessStatusCode) {
            throw "Router landing page failed (HTTP $([int]$landing.StatusCode))."
        }
        $loginParams = [ordered]@{ id = $Username; pw = $Password }
        if ($TakeOver) {
            $loginParams.flush = $true
        }
        $login = Invoke-RouterRpc -Client $client -Origin $Origin -Method 'session/login' -Params $loginParams
        if ($login -ne 'done') {
            throw 'Router authentication did not complete.'
        }
        return $client
    } catch {
        $client.Dispose()
        throw
    }
}

function Disconnect-Router {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin
    )

    try {
        Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'session/logout' | Out-Null
    } catch {
    } finally {
        $Client.Dispose()
    }
}

function Get-SettingsSnapshot {
    param(
        [object]$SystemInfo,
        [object]$WanInfo,
        [object]$CwmpInfo
    )

    $easyMeshProperty = $SystemInfo.PSObject.Properties['easymesh']
    $wirelessProperty = $SystemInfo.PSObject.Properties['wireless']
    if ($null -ne $easyMeshProperty -and $null -ne $easyMeshProperty.Value) {
        $easyMesh = $easyMeshProperty.Value
        $meshInfo = if ($null -eq $easyMesh.info) { @() } else { @($easyMesh.info) }
        $easyMeshRole = [string]$easyMesh.role
        $bands = @($meshInfo | ForEach-Object {
            [ordered]@{
                band = [string]$_.band
                enable = [bool]$_.enable
                ssid = [string]$_.ssid
            }
        } | Sort-Object band, ssid)
    } elseif ($null -ne $wirelessProperty -and $null -ne $wirelessProperty.Value) {
        $easyMeshRole = 'disabled'
        $bands = @($wirelessProperty.Value | ForEach-Object {
            $band = [string]$_.band
            @($_.bss) | ForEach-Object {
                [ordered]@{
                    band = $band
                    enable = [bool]$_.enable
                    ssid = [string]$_.ssid
                }
            }
        } | Sort-Object band, ssid)
    } else {
        throw 'The router returned no EasyMesh or wireless settings.'
    }
    $acsUrlProperty = $CwmpInfo.PSObject.Properties['acs_url']
    $acsUrl = if ($null -eq $acsUrlProperty) { '' } else { [string]$acsUrlProperty.Value }

    [ordered]@{
        version = [string]$SystemInfo.version
        remotePort = [int]$SystemInfo.remote_port
        dhcpEnabled = [bool]$SystemInfo.dhcpd.enable
        dhcpStart = [string]$SystemInfo.dhcpd.startip
        dhcpEnd = [string]$SystemInfo.dhcpd.endip
        easyMeshRole = $easyMeshRole
        bands = $bands
        cwmp = [ordered]@{
            active = [bool]$CwmpInfo.active
            acsConfigured = -not [string]::IsNullOrWhiteSpace($acsUrl)
        }
        wan = [ordered]@{
            type = [string]$WanInfo.type
            ifname = [string]$WanInfo.ifname
            mac = [string]$WanInfo.mac
            originalMac = [string]$WanInfo.org_mac
            ip = [string]$WanInfo.ip
            mask = [string]$WanInfo.mask
            gateway = [string]$WanInfo.gateway
            mtu = [int]$WanInfo.mtu
            device = [string]$WanInfo.device
        }
    }
}

function Save-RouterBackup {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [string]$Directory,
        [string]$SafeLabel,
        [object]$SystemInfo,
        [object]$FirmwareInfo,
        [object]$Settings
    )

    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $configBase64 = [string](Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'config/backup')
    [byte[]]$configBytes = [Convert]::FromBase64String($configBase64)
    if ($configBytes.Length -lt 64 -or [Text.Encoding]::ASCII.GetString($configBytes, 0, 6) -ne 'GEN_V2') {
        throw 'The router returned an invalid configuration backup.'
    }

    $configPath = Join-Path $Directory "$SafeLabel-$stamp.cfg"
    $statePath = Join-Path $Directory "$SafeLabel-$stamp-state.json"
    [IO.File]::WriteAllBytes($configPath, $configBytes)
    $state = [ordered]@{
        capturedAt = (Get-Date).ToString('o')
        target = $TargetLabel
        baseUri = $Origin
        settings = $Settings
        firmware = $FirmwareInfo
        configBackup = [ordered]@{
            path = $configPath
            bytes = $configBytes.Length
            sha256 = (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash
        }
    }
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    return $state
}

function Test-HostOnline {
    param([string]$Address)

    $ping = [Net.NetworkInformation.Ping]::new()
    try {
        $reply = $ping.Send($Address, 1000)
        return $reply.Status -eq [Net.NetworkInformation.IPStatus]::Success
    } catch {
        return $false
    } finally {
        $ping.Dispose()
    }
}

function Assert-HostOffline {
    param(
        [string]$Address,
        [int]$Attempts = 3
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        if (Test-HostOnline -Address $Address) {
            throw "The WOL verification PC is already online: $Address."
        }
        if ($attempt -lt $Attempts) {
            Start-Sleep -Seconds 5
        }
    }
}

function Get-StationCount {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin
    )

    $envelope = Invoke-RouterRpc -Client $Client -Origin $Origin `
        -Method 'network/interface/lan/stations' -Envelope
    $stations = $envelope.Value
    if ($null -eq $stations -or $stations -isnot [Array]) {
        throw 'The router returned an invalid LAN station list.'
    }
    return $stations.Count
}

function Test-UpgradeFailure {
    param([AllowNull()][object]$Status)

    if ($null -eq $Status) {
        return $false
    }
    $property = $Status.PSObject.Properties['failed']
    if ($null -eq $property -or $null -eq $property.Value) {
        return $false
    }
    $value = $property.Value
    if ($value -is [bool]) {
        return $value
    }
    if ($value -is [string]) {
        return -not [string]::IsNullOrWhiteSpace($value)
    }
    return @($value).Count -gt 0
}

function Start-ManualUpgradePreparation {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [TimeSpan]$Timeout = [TimeSpan]::FromSeconds(20)
    )

    $result = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'firmware/upgrade/prepare' `
        -Params ([ordered]@{ method = 'manual' })
    if ($result -ne 'done') {
        throw "The router rejected firmware upgrade preparation: $result."
    }

    $deadline = [DateTime]::UtcNow.Add($Timeout)
    do {
        $status = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'firmware/upgrade/status'
        if (Test-UpgradeFailure -Status $status) {
            throw "The router failed while preparing the upgrade: $($status.failed)."
        }
        if ([string]$status.stage -eq 'starting') {
            return
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'The router did not enter the prepared upgrade state.'
}

function Write-RolloutRecord {
    param(
        [string]$Directory,
        [string]$SafeLabel,
        [string]$Status,
        [string]$Phase,
        [string]$Origin,
        [bool]$IsRollback,
        [string]$FirmwareSha256,
        [AllowNull()][object]$Backup,
        [AllowNull()][object]$Restart,
        [AllowNull()][string]$TestMac,
        [AllowNull()][string]$TestPingAddress,
        [AllowNull()][string]$Message
    )

    New-Item -ItemType Directory -Path $Directory -Force | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $path = Join-Path $Directory "$SafeLabel-$stamp-$Status.json"
    $backupPath = if ($null -eq $Backup) { $null } else { [string]$Backup.configBackup.path }
    $restartObserved = if ($null -eq $Restart) { $false } else { [bool]$Restart.RestartObserved }
    $upgradeStatusObserved = if ($null -eq $Restart) { $false } else { [bool]$Restart.UpgradeStatusObserved }
    $record = [ordered]@{
        recordedAt = (Get-Date).ToString('o')
        target = $TargetLabel
        baseUri = $Origin
        status = $Status
        phase = $Phase
        rollback = $IsRollback
        firmwareSha256 = $FirmwareSha256
        backupPath = $backupPath
        restartObserved = $restartObserved
        upgradeStatusObserved = $upgradeStatusObserved
        testMac = $TestMac
        testPingAddress = $TestPingAddress
        message = $Message
    }
    [IO.File]::WriteAllText($path, ($record | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    return $path
}

function Wait-RouterRestart {
    param(
        [string]$Origin,
        [Net.Http.HttpClient]$StatusClient,
        [TimeSpan]$Timeout = [TimeSpan]::FromMinutes(5)
    )

    $deadline = [DateTime]::UtcNow.Add($Timeout)
    $downSeen = $false
    $upgradeStatusSeen = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not $downSeen -and $null -ne $StatusClient) {
            try {
                $status = Invoke-RouterRpc -Client $StatusClient -Origin $Origin `
                    -Method 'firmware/upgrade/status' -TimeoutSeconds 3
                if (Test-UpgradeFailure -Status $status) {
                    throw 'The router reported a firmware upgrade failure.'
                }
                $stageProperty = $status.PSObject.Properties['stage']
                if ($null -ne $stageProperty -and $stageProperty.Value -ne 'idle') {
                    $upgradeStatusSeen = $true
                }
            } catch {
                if ($_.Exception.Message -eq 'The router reported a firmware upgrade failure.') {
                    throw
                }
            }
        }

        $probe = [Net.Http.HttpClient]::new()
        $probe.Timeout = [TimeSpan]::FromSeconds(3)
        try {
            $response = $probe.GetAsync($Origin + '/ui/').GetAwaiter().GetResult()
            $online = $response.IsSuccessStatusCode
        } catch {
            $online = $false
        } finally {
            $probe.Dispose()
        }

        if (-not $online) {
            $downSeen = $true
        } elseif ($downSeen) {
            return [pscustomobject]@{
                RestartObserved = $true
                Online = $true
                UpgradeStatusObserved = $upgradeStatusSeen
            }
        }
        Start-Sleep -Seconds 2
    }
    return [pscustomobject]@{
        RestartObserved = $downSeen
        Online = $false
        UpgradeStatusObserved = $upgradeStatusSeen
    }
}

function Connect-RouterUntilReady {
    param(
        [string]$Origin,
        [string]$Username,
        [string]$Password,
        [TimeSpan]$Timeout = [TimeSpan]::FromMinutes(1)
    )

    $deadline = [DateTime]::UtcNow.Add($Timeout)
    do {
        try {
            return Connect-Router -Origin $Origin -Username $Username -Password $Password -TakeOver $true
        } catch {
            Start-Sleep -Seconds 3
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'The router web service returned, but login did not become ready within one minute.'
}

function Get-StableRouterState {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [TimeSpan]$Timeout = [TimeSpan]::FromMinutes(1)
    )

    $deadline = [DateTime]::UtcNow.Add($Timeout)
    do {
        $systemFirst = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'system/info'
        $wanFirst = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'network/interface/wan1/info'
        $cwmpFirst = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'cwmp/get'
        $settingsFirst = Get-SettingsSnapshot -SystemInfo $systemFirst -WanInfo $wanFirst -CwmpInfo $cwmpFirst
        Start-Sleep -Seconds 5
        $systemSecond = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'system/info'
        $wanSecond = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'network/interface/wan1/info'
        $cwmpSecond = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'cwmp/get'
        $settingsSecond = Get-SettingsSnapshot -SystemInfo $systemSecond -WanInfo $wanSecond -CwmpInfo $cwmpSecond
        if (($settingsFirst | ConvertTo-Json -Depth 12 -Compress) -ceq
                ($settingsSecond | ConvertTo-Json -Depth 12 -Compress)) {
            return [pscustomobject]@{
                SystemInfo = $systemSecond
                WanInfo = $wanSecond
                CwmpInfo = $cwmpSecond
                Settings = $settingsSecond
            }
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'The router settings did not stabilize within one minute after restart.'
}

function Send-TestWake {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [string]$Mac
    )

    $normalized = $Mac.Replace(':', '-').ToUpperInvariant()
    $registered = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/show'
    $known = @($registered | ForEach-Object { ([string]$_.mac).Replace(':', '-').ToUpperInvariant() })
    $added = $false
    if ($normalized -notin $known) {
        $suffix = $normalized.Substring([Math]::Max(0, $normalized.Length - 8)).Replace('-', '')
        $result = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/add' -Params ([ordered]@{
            mac = $normalized
            pcname = "ClassroomDashboard-$suffix"
        })
        if ($result -ne 'done') {
            throw 'The router did not register the WOL test target.'
        }
        $added = $true
    }

    try {
        $signalResult = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/signal' -Params @($normalized)
        if ($signalResult -ne 'done') {
            throw 'The router did not accept the WOL signal.'
        }
    } catch {
        if ($added) {
            try {
                Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/del' -Params @($normalized) | Out-Null
            } catch {
            }
        }
        throw
    }
    return [pscustomobject]@{ Mac = $normalized; Added = $added }
}

function Remove-TestWake {
    param(
        [Net.Http.HttpClient]$Client,
        [string]$Origin,
        [string]$Mac
    )

    $result = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/del' -Params @($Mac)
    if ($result -ne 'done') {
        throw 'The router did not remove the temporary WOL target.'
    }
    $registered = Invoke-RouterRpc -Client $Client -Origin $Origin -Method 'wol/show'
    $known = @($registered | ForEach-Object { ([string]$_.mac).Replace(':', '-').ToUpperInvariant() })
    if ($Mac -in $known) {
        throw 'The temporary WOL target still exists after deletion.'
    }
}

if ([string]::IsNullOrWhiteSpace($RouterUser)) {
    $RouterUser = Read-Host 'Router username'
}
if ($null -eq $RouterPassword) {
    $RouterPassword = Read-Host 'Router password' -AsSecureString
}

$origin = $BaseUri.TrimEnd('/')
$targetHost = ([Uri]$origin).Host
$is214Target = $targetHost -eq '223.194.167.253' -or $TargetLabel -match '(?i)^214(?:$|[-_ ])'
if ($is214Target -and -not $Allow214) {
    throw '214 classroom equipment is excluded. Allow214 is required to target it explicitly.'
}
if ($SkipWolVerification -and -not $is214Target) {
    throw 'SkipWolVerification is restricted to the explicitly approved 214 target.'
}
if ($SkipWolVerification -and (-not $Apply -or $Rollback)) {
    throw 'SkipWolVerification is valid only for a WAN WOL firmware rollout.'
}
$safeLabel = ($TargetLabel -replace '[^A-Za-z0-9_.-]', '_').Trim('_')
if ([string]::IsNullOrWhiteSpace($safeLabel)) {
    $safeLabel = 'router'
}
$firmware = Get-IptimeFirmwareMetadata -Path $FirmwarePath
if ($firmware.Model -ne 'ax3000q' -or $firmware.Version -ne '15_258') {
    throw "Unexpected firmware identity: $($firmware.Model) $($firmware.Version)."
}
if (-not $firmware.CrcValid) {
    throw "Firmware CRC mismatch: stored $($firmware.StoredCrc), computed $($firmware.ComputedCrc)."
}
if ($firmware.DeclaredPayloadSize -ne [uint64]($firmware.Length - 56)) {
    throw "Firmware payload size mismatch: $($firmware.DeclaredPayloadSize) != $($firmware.Length - 56)."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedSha256) -and
        $firmware.Sha256 -ne $ExpectedSha256.Trim().ToUpperInvariant()) {
    throw "Firmware SHA-256 mismatch: $($firmware.Sha256)."
}
if ($Apply -and [string]::IsNullOrWhiteSpace($ExpectedSha256)) {
    throw 'ExpectedSha256 is required when Apply is used.'
}
if ($Apply -and [string]::IsNullOrWhiteSpace($ExpectedWanMac)) {
    throw 'ExpectedWanMac is required when Apply is used.'
}
if ($Apply -and -not $Rollback -and
        $firmware.Sha256 -ne '63C9F15177E39787AFAF51DFD116DB6A426C996C2736F5A242C9C760BE0966B0') {
    throw 'Apply requires the fixed WAN WOL firmware image.'
}
if ($Apply -and $Rollback -and
        $firmware.Sha256 -ne 'BB558444A09FF1264AEA7BA683CD68E4ECC77DB05704809A2D0774DE5050A129') {
    throw 'Rollback requires the fixed official 15.25.8 firmware image.'
}
if ($Apply -and $ConfirmTarget -cne $TargetLabel) {
    throw 'ConfirmTarget must exactly match TargetLabel when Apply is used.'
}
if ($Apply -and -not $Rollback -and -not $SkipWolVerification -and
        ([string]::IsNullOrWhiteSpace($TestMac) -or [string]::IsNullOrWhiteSpace($TestPingAddress))) {
    throw 'TestMac and TestPingAddress are required when Apply is used.'
}
if ($Apply -and -not $Rollback -and -not $SkipWolVerification) {
    $routerOctets = $targetHost.Split('.')
    $testOctets = $TestPingAddress.Split('.')
    if ($routerOctets.Count -ne 4 -or $testOctets.Count -ne 4 -or
            ($routerOctets[0..2] -join '.') -ne ($testOctets[0..2] -join '.')) {
        throw 'TestPingAddress must be in the same /24 as the router WAN address.'
    }
}

$plainPassword = Get-PlainTextPassword -Value $RouterPassword
$client = $null
$wakeRegistration = $null
$backupDirectory = Join-Path $BackupRoot ((Get-Date -Format 'yyyy-MM-dd') + '-wanwol-rollout')
$backup = $null
$restart = $null
$rolloutPhase = 'connect'
try {
    $client = Connect-Router -Origin $origin -Username $RouterUser -Password $plainPassword -TakeOver $TakeOverSession.IsPresent
    $systemBefore = Invoke-RouterRpc -Client $client -Origin $origin -Method 'system/info'
    $firmwareBefore = Invoke-RouterRpc -Client $client -Origin $origin -Method 'firmware/info'
    $wanBefore = Invoke-RouterRpc -Client $client -Origin $origin -Method 'network/interface/wan1/info'
    $cwmpBefore = Invoke-RouterRpc -Client $client -Origin $origin -Method 'cwmp/get'
    $stationCount = Get-StationCount -Client $client -Origin $origin
    $settingsBefore = Get-SettingsSnapshot -SystemInfo $systemBefore -WanInfo $wanBefore -CwmpInfo $cwmpBefore
    $backup = Save-RouterBackup -Client $client -Origin $origin -Directory $backupDirectory `
        -SafeLabel $safeLabel -SystemInfo $systemBefore -FirmwareInfo $firmwareBefore -Settings $settingsBefore
    $rolloutPhase = 'preflight'

    if ([uint64]$firmware.Length -gt [uint64]$firmwareBefore.max_size) {
        throw "Firmware is larger than the router limit: $($firmware.Length) > $($firmwareBefore.max_size)."
    }
    if ($firmwareBefore.version -ne '15.258' -or
            $firmwareBefore.snapshot -ne 'd2336d85840352681a65a5c1aec55d6dcc0930d6') {
        throw "Unexpected installed firmware identity: $($firmwareBefore.version) $($firmwareBefore.snapshot)."
    }
    if ($wanBefore.ifname -ne 'lan0' -or $wanBefore.ip -ne ([Uri]$origin).Host) {
        throw "Unexpected WAN identity: $($wanBefore.ifname) $($wanBefore.ip)."
    }
    $actualWanMac = ([string]$wanBefore.mac).Replace('-', ':').ToUpperInvariant()
    if (-not [string]::IsNullOrWhiteSpace($ExpectedWanMac) -and
            $actualWanMac -ne $ExpectedWanMac.Replace('-', ':').ToUpperInvariant()) {
        throw "Unexpected WAN MAC: $actualWanMac."
    }
    if ($Apply -and -not [bool]$wanBefore.link) {
        throw 'The router WAN link is down.'
    }
    $acsUrlProperty = $cwmpBefore.PSObject.Properties['acs_url']
    $acsUrl = if ($null -eq $acsUrlProperty) { '' } else { [string]$acsUrlProperty.Value }
    if ([bool]$cwmpBefore.active -or -not [string]::IsNullOrWhiteSpace($acsUrl)) {
        throw 'CWMP automatic management must be disabled before rollout.'
    }
    if (-not $AllowConnectedStations -and $stationCount -gt 0) {
        throw "Connected LAN stations detected: $stationCount."
    }

    Write-Host "Preflight passed: $TargetLabel"
    Write-Host "  Router: $origin"
    Write-Host "  Current: $($systemBefore.version), EasyMesh $($settingsBefore.easyMeshRole)"
    Write-Host "  SSID: $((@($settingsBefore.bands | ForEach-Object ssid) | Sort-Object -Unique) -join ', ')"
    Write-Host "  WAN: $($wanBefore.ifname) $($wanBefore.ip) $actualWanMac"
    Write-Host '  CWMP remote management: disabled'
    Write-Host "  Connected stations: $stationCount"
    Write-Host "  Backup: $($backup.configBackup.path)"
    Write-Host "  Firmware SHA-256: $($firmware.Sha256)"

    if (-not $Apply) {
        Write-Host 'Dry run completed. No firmware was uploaded.'
        return
    }

    [byte[]]$firmwareBytes = [IO.File]::ReadAllBytes($firmware.Path)
    $rolloutPhase = 'upload'
    # AX3000Q's legacy CGI parser treats quoted boundary values as literal text.
    $multipartBoundary = '--------------------------' + [Guid]::NewGuid().ToString('N')
    $multipart = [Net.Http.MultipartFormDataContent]::new($multipartBoundary)
    $boundaryParameter = $multipart.Headers.ContentType.Parameters | Select-Object -First 1
    $boundaryParameter.Value = $multipartBoundary
    $fileContent = [Net.Http.ByteArrayContent]::new($firmwareBytes)
    $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
    $multipart.Add($fileContent, 'file', 'upgrade')
    try {
        if (-not $Rollback -and -not $SkipWolVerification) {
            Assert-HostOffline -Address $TestPingAddress
        }
        $stationCount = Get-StationCount -Client $client -Origin $origin
        if (-not $AllowConnectedStations -and $stationCount -gt 0) {
            throw "Connected LAN stations appeared before upload: $stationCount."
        }
        $upgradeStatus = Invoke-RouterRpc -Client $client -Origin $origin -Method 'firmware/upgrade/status'
        $upgradeStage = [string]$upgradeStatus.stage
        if ($upgradeStage -eq 'starting') {
            if (-not $ResumePreparedUpgrade) {
                throw 'A prepared upgrade is already waiting. Re-run with ResumePreparedUpgrade only after confirming its origin.'
            }
            Write-Warning 'Resuming the existing prepared firmware upload for this confirmed target.'
        } elseif ($upgradeStage -eq 'idle') {
            Start-ManualUpgradePreparation -Client $client -Origin $origin
        } elseif ($upgradeStage -eq 'failed' -and [string]$upgradeStatus.failed -eq 'format') {
            if (-not $ResumePreparedUpgrade) {
                throw 'The previous upload failed format validation. Re-run with ResumePreparedUpgrade only after fixing and verifying the upload body.'
            }
            Write-Warning 'Resetting the confirmed target after the previous malformed multipart upload.'
            Start-ManualUpgradePreparation -Client $client -Origin $origin
        } else {
            throw "Unexpected firmware upgrade stage before upload: $upgradeStage."
        }

        $uploadTimeout = [Threading.CancellationTokenSource]::new([TimeSpan]::FromMinutes(3))
        try {
            $upload = $client.PostAsync(
                $origin + '/cgi/firmware.cgi', $multipart, $uploadTimeout.Token).GetAwaiter().GetResult()
            if (-not $upload.IsSuccessStatusCode) {
                throw "Firmware upload failed (HTTP $([int]$upload.StatusCode))."
            }
        } catch [Net.Http.HttpRequestException] {
            Write-Warning 'The upload connection closed before a response. Checking for the expected router restart.'
        } catch [Threading.Tasks.TaskCanceledException] {
            Write-Warning 'The upload response timed out. Checking for the expected router restart.'
        } finally {
            $uploadTimeout.Dispose()
        }
    } finally {
        $multipart.Dispose()
    }

    $rolloutPhase = 'restart'
    $restart = Wait-RouterRestart -Origin $origin -StatusClient $client
    $client.Dispose()
    $client = $null
    if (-not $restart.Online) {
        throw 'The router did not return within five minutes. Physical recovery may be required.'
    }

    $client = Connect-RouterUntilReady -Origin $origin -Username $RouterUser -Password $plainPassword
    $stableAfter = Get-StableRouterState -Client $client -Origin $origin
    $systemAfter = $stableAfter.SystemInfo
    $wanAfter = $stableAfter.WanInfo
    $settingsAfter = $stableAfter.Settings
    $firmwareAfter = Invoke-RouterRpc -Client $client -Origin $origin -Method 'firmware/info'
    if ($firmwareAfter.version -ne '15.258' -or
            $firmwareAfter.snapshot -ne 'd2336d85840352681a65a5c1aec55d6dcc0930d6') {
        throw 'The router returned with an unexpected firmware identity.'
    }
    if (-not [bool]$wanAfter.link) {
        throw 'The router returned, but its WAN link is down.'
    }
    $beforeJson = $settingsBefore | ConvertTo-Json -Depth 12 -Compress
    $afterJson = $settingsAfter | ConvertTo-Json -Depth 12 -Compress
    if ($beforeJson -cne $afterJson) {
        throw "Router settings changed after upgrade. Restore from $($backup.configBackup.path)."
    }

    if (-not $Rollback -and -not $SkipWolVerification) {
        $rolloutPhase = 'wol'
        $wakeRegistration = Send-TestWake -Client $client -Origin $origin -Mac $TestMac
        $wakeDeadline = [DateTime]::UtcNow.AddMinutes(3)
        $consecutiveOnline = 0
        while ([DateTime]::UtcNow -lt $wakeDeadline) {
            if (Test-HostOnline -Address $TestPingAddress) {
                $consecutiveOnline++
                if ($consecutiveOnline -ge 3) {
                    break
                }
            } else {
                $consecutiveOnline = 0
            }
            Start-Sleep -Seconds 5
        }
        if ($consecutiveOnline -lt 3) {
            throw "The router accepted WOL, but $TestPingAddress did not become reachable."
        }
        if ($wakeRegistration.Added) {
            Remove-TestWake -Client $client -Origin $origin -Mac $wakeRegistration.Mac
            $wakeRegistration = $null
        }
    }

    $rolloutPhase = 'completed'
    $verificationMessage = if ($SkipWolVerification) {
        'Router restart and settings verification completed; WAN WOL skipped by explicit 214 maintenance instruction.'
    } else {
        'Verification completed.'
    }
    $recordPath = Write-RolloutRecord -Directory $backupDirectory -SafeLabel $safeLabel `
        -Status 'success' -Phase $rolloutPhase -Origin $origin -IsRollback $Rollback.IsPresent `
        -FirmwareSha256 $firmware.Sha256 -Backup $backup -Restart $restart `
        -TestMac $TestMac -TestPingAddress $TestPingAddress -Message $verificationMessage

    Write-Host $(if ($Rollback) {
            "Rollback restart/settings verified: $TargetLabel"
        } elseif ($SkipWolVerification) {
            "Rollout restart/settings verified: $TargetLabel"
        } else {
            "Rollout verified: $TargetLabel"
        })
    Write-Host "  Restart observed: $($restart.RestartObserved)"
    Write-Host "  Upgrade status observed: $($restart.UpgradeStatusObserved)"
    Write-Host "  Critical settings preserved: yes"
    if ($Rollback) {
        Write-Host '  WAN WOL test: skipped for official firmware'
    } elseif ($SkipWolVerification) {
        Write-Host '  WAN WOL test: skipped by explicit 214 maintenance instruction'
    } else {
        Write-Host "  WOL target online: $TestPingAddress"
    }
    Write-Host "  Result record: $recordPath"
} catch {
    $failure = $_
    if ($Apply) {
        try {
            $recordPath = Write-RolloutRecord -Directory $backupDirectory -SafeLabel $safeLabel `
                -Status 'failed' -Phase $rolloutPhase -Origin $origin -IsRollback $Rollback.IsPresent `
                -FirmwareSha256 $firmware.Sha256 -Backup $backup -Restart $restart `
                -TestMac $TestMac -TestPingAddress $TestPingAddress -Message $failure.Exception.Message
            Write-Warning "Failure record: $recordPath"
        } catch {
        }
    }
    throw $failure
} finally {
    $plainPassword = $null
    if ($null -ne $client) {
        if ($null -ne $wakeRegistration -and $wakeRegistration.Added) {
            try {
                Remove-TestWake -Client $client -Origin $origin -Mac $wakeRegistration.Mac
            } catch {
            }
        }
        Disconnect-Router -Client $client -Origin $origin
    }
}

param(
    [string]$Base = "http://localhost:8080/api/v1",
    [string]$InstallKey = "INST-CHANGE-ME-BEFORE-DEPLOY",
    [string]$AdminUser = "admin",
    [string]$AdminPass = "password"
)

$ErrorActionPreference = "Stop"

function Send-Json {
    param(
        [System.Net.Http.HttpClient]$Client,
        [string]$Method,
        [string]$Url,
        [string]$Body = $null,
        [hashtable]$Headers = $null
    )

    $req = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, $Url)
    if ($null -ne $Body) {
        $req.Content = [System.Net.Http.StringContent]::new($Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    if ($Headers) {
        foreach ($k in $Headers.Keys) {
            if ($k -eq "Content-Type") { continue }
            $req.Headers.TryAddWithoutValidation($k, [string]$Headers[$k]) | Out-Null
        }
    }

    $resp = $Client.SendAsync($req).GetAwaiter().GetResult()
    $txt = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    [pscustomobject]@{ StatusCode = [int]$resp.StatusCode; Body = $txt }
}

function Ensure-2xx {
    param($Resp, [string]$Label)
    if ($Resp.StatusCode -lt 200 -or $Resp.StatusCode -ge 300) {
        throw "$Label failed: HTTP $($Resp.StatusCode) $($Resp.Body)"
    }
}

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(8)

# health wait
$health = $Base.Replace('/api/v1', '') + '/v3/api-docs'
$ready = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $r = $client.GetAsync($health).GetAwaiter().GetResult()
        if ([int]$r.StatusCode -eq 200) { $ready = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 500
}
if (-not $ready) {
    throw "Backend not ready at $health"
}

$mac = "AA:BB:CC:DD:EE:F7"
$octet = Get-Random -Minimum 100 -Maximum 220
$prefix = "10.254.$octet."
$ip = $prefix + "17"

# login
$loginBody = (@{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json -Compress)
$login = Send-Json -Client $client -Method "Post" -Url "$Base/auth/login" -Body $loginBody
Ensure-2xx $login "login"
$token = ($login.Body | ConvertFrom-Json).token
$auth = @{ Authorization = "Bearer $token" }

# create group
$gName = "e2e-todo-" + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$groupBody = (@{ name = $gName; description = "e2e" } | ConvertTo-Json -Compress)
$group = Send-Json -Client $client -Method "Post" -Url "$Base/groups" -Headers $auth -Body $groupBody
Ensure-2xx $group "create group"
$groupId = [long](($group.Body | ConvertFrom-Json).id)

# register agent
$regHeaders = @{ "X-Agent-Install-Key" = $InstallKey }
$regBody = (@{
    macAddress = $mac
    hostname = "todo-host"
    ipAddress = $ip
    osInfo = "Windows"
    agentVersion = "1.0.0"
} | ConvertTo-Json -Compress)
$reg = Send-Json -Client $client -Method "Post" -Url "$Base/agent/register" -Headers $regHeaders -Body $regBody
if ($reg.StatusCode -ne 201) {
    throw "register failed: HTTP $($reg.StatusCode) $($reg.Body)"
}
$agentToken = ($reg.Body | ConvertFrom-Json).agentToken
$hbHeaders = @{ "X-Agent-Token" = $agentToken }

function Send-Hb {
    param($Client, $Base, $Headers, $Mac, $Ip)
    $body = (@{
        macAddress = $Mac
        hostname = "todo-host"
        ipAddress = $Ip
        agentVersion = "1.0.0"
        inboundKbps = 123
        outboundKbps = 321
    } | ConvertTo-Json -Compress)
    $r = Send-Json -Client $Client -Method "Post" -Url "$Base/heartbeat" -Headers $Headers -Body $body
    if ($r.StatusCode -ne 200) {
        throw "heartbeat failed: HTTP $($r.StatusCode) $($r.Body)"
    }
}

function Get-GroupId {
    param($Client, $Base, $Auth, $Mac)
    $s = Send-Json -Client $Client -Method "Get" -Url "$Base/status/$Mac" -Headers $Auth
    Ensure-2xx $s "status"
    ($s.Body | ConvertFrom-Json).groupId
}

# G1: no range => null
Send-Hb $client $Base $hbHeaders $mac $ip
Start-Sleep -Milliseconds 250
$g1 = Get-GroupId $client $Base $auth $mac

# create range
$rangeBody = (@{ groupId = $groupId; cidr = ($prefix + "0/24"); description = "todo" } | ConvertTo-Json -Compress)
$range = Send-Json -Client $client -Method "Post" -Url "$Base/ip-ranges" -Headers $auth -Body $rangeBody
Ensure-2xx $range "create range"
$rangeId = [long](($range.Body | ConvertFrom-Json).id)

# G2: assigned
Send-Hb $client $Base $hbHeaders $mac $ip
Start-Sleep -Milliseconds 250
$g2 = Get-GroupId $client $Base $auth $mac

# delete range
$del = Send-Json -Client $client -Method "Delete" -Url "$Base/ip-ranges/$rangeId" -Headers $auth
Ensure-2xx $del "delete range"

# G3: unassigned
Send-Hb $client $Base $hbHeaders $mac $ip
Start-Sleep -Milliseconds 250
$g3 = Get-GroupId $client $Base $auth $mac

# batch range create
$batchBody = (@{ groupId = $groupId; basePrefix = $prefix; rangeSpec = "17-17"; description = "todo-batch" } | ConvertTo-Json -Compress)
$batch = Send-Json -Client $client -Method "Post" -Url "$Base/ip-ranges/batch" -Headers $auth -Body $batchBody
Ensure-2xx $batch "batch create"

# G4: assigned again
Send-Hb $client $Base $hbHeaders $mac $ip
Start-Sleep -Milliseconds 250
$g4 = Get-GroupId $client $Base $auth $mac

# audit query path
Start-Sleep -Seconds 3
$audit = Send-Json -Client $client -Method "Get" -Url "$Base/admin/audit/logs?action=PC_GROUP_REASSIGNED&limit=200" -Headers $auth
Ensure-2xx $audit "audit query"
$rows = @($audit.Body | ConvertFrom-Json)
$actorRows = @($rows | Where-Object { $_.actor -eq $mac })

Write-Host "GROUP_ID=$groupId"
Write-Host "G1=$g1"
Write-Host "G2=$g2"
Write-Host "G3=$g3"
Write-Host "G4=$g4"
Write-Host "AUDIT_ROWS_FOR_MAC=$($actorRows.Count)"

if ($null -ne $g1) { throw "Expected G1 null, got $g1" }
if ([long]$g2 -ne $groupId) { throw "Expected G2=$groupId, got $g2" }
if ($null -ne $g3) { throw "Expected G3 null, got $g3" }
if ([long]$g4 -ne $groupId) { throw "Expected G4=$groupId, got $g4" }
if ($actorRows.Count -lt 2) { throw "Expected >=2 audit rows for reassignment, got $($actorRows.Count)" }

Write-Host "E2E_AND_AUDIT_OK"

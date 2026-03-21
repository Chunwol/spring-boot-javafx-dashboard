param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Username = "admin",
    [string]$Password = "password",
    [switch]$IncludeMutating
)

$ErrorActionPreference = "Stop"

function Assert-Status {
    param(
        [int]$Actual,
        [int[]]$Expected,
        [string]$Label
    )
    if ($Expected -notcontains $Actual) {
        throw "$Label failed. Expected: $($Expected -join ', ') / Actual: $Actual"
    }
}

function Invoke-Http {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $params = @{
        Uri = $Url
        Method = $Method
        UseBasicParsing = $true
        Headers = $Headers
    }

    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Depth 8)
    }

    try {
        return Invoke-WebRequest @params
    } catch {
        if ($_.Exception.Response) {
            $response = $_.Exception.Response
            $reader = New-Object System.IO.StreamReader($response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $reader.Close()
            return [pscustomobject]@{
                StatusCode = [int]$response.StatusCode
                Content = $content
            }
        }
        throw
    }
}

function Parse-JsonSafe {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    try {
        return $Text | ConvertFrom-Json
    } catch {
        return $null
    }
}

Write-Host "[1/7] Login and get JWT token"
$loginResp = Invoke-Http -Method POST -Url "$BaseUrl/auth/login" -Body @{
    username = $Username
    password = $Password
}
Assert-Status -Actual $loginResp.StatusCode -Expected @(200) -Label "POST /auth/login"
$loginJson = Parse-JsonSafe -Text $loginResp.Content
if ($null -eq $loginJson -or [string]::IsNullOrWhiteSpace($loginJson.token)) {
    throw "Login response does not contain token"
}
$token = [string]$loginJson.token
$authHeaders = @{ Authorization = "Bearer $token" }

Write-Host "[2/7] Read install key"
$keyResp = Invoke-Http -Method GET -Url "$BaseUrl/admin/install-key" -Headers $authHeaders
Assert-Status -Actual $keyResp.StatusCode -Expected @(200) -Label "GET /admin/install-key"
$keyJson = Parse-JsonSafe -Text $keyResp.Content
if ($null -eq $keyJson -or [string]::IsNullOrWhiteSpace($keyJson.installKey)) {
    throw "Install key is missing"
}
$installKey = [string]$keyJson.installKey

Write-Host "[3/7] Fetch admin agents list"
$agentsResp = Invoke-Http -Method GET -Url "$BaseUrl/admin/agents" -Headers $authHeaders
Assert-Status -Actual $agentsResp.StatusCode -Expected @(200) -Label "GET /admin/agents"
$agents = Parse-JsonSafe -Text $agentsResp.Content
if ($null -eq $agents) {
    throw "Admin agents response is not JSON"
}

$orphanCount = 0
foreach ($row in $agents) {
    if ($row.PSObject.Properties.Name -contains "orphan") {
        if ($row.orphan -eq $true) {
            $orphanCount++
        }
    }
}
Write-Host "Found agents: $($agents.Count), orphan rows: $orphanCount"

Write-Host "[4/7] Validate response shape"
foreach ($row in $agents) {
    if (-not ($row.PSObject.Properties.Name -contains "macAddress")) {
        throw "Agent row missing macAddress"
    }
    if (-not ($row.PSObject.Properties.Name -contains "tokenActive")) {
        throw "Agent row missing tokenActive"
    }
    if (-not ($row.PSObject.Properties.Name -contains "orphan")) {
        throw "Agent row missing orphan"
    }
}

if (-not $IncludeMutating) {
    Write-Host "[5/7] Mutating tests skipped (use -IncludeMutating to run full cycle)"
    Write-Host "[6/7] No-op"
    Write-Host "[7/7] Done"
    Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
    exit 0
}

# Mutating flow: register -> verify -> revoke -> delete -> verify
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$mac = "AA:BB:CC:" + ($stamp % 100).ToString("00") + ":11:22"
$hostName = "SMOKE-$stamp"
$ip = "10.255.255." + (($stamp % 200) + 30)

Write-Host "[5/7] Register temporary agent ($mac)"
$regResp = Invoke-Http -Method POST -Url "$BaseUrl/agent/register" -Headers @{ "X-Agent-Install-Key" = $installKey } -Body @{
    macAddress = $mac
    hostname = $hostName
    ipAddress = $ip
    osInfo = "Windows Smoke"
    agentVersion = "smoke"
}
Assert-Status -Actual $regResp.StatusCode -Expected @(200, 201) -Label "POST /agent/register"

Write-Host "[6/7] Revoke and delete temporary agent"
$revokeResp = Invoke-Http -Method PUT -Url "$BaseUrl/admin/agents/$mac/revoke" -Headers $authHeaders
Assert-Status -Actual $revokeResp.StatusCode -Expected @(200) -Label "PUT /admin/agents/{mac}/revoke"
$deleteResp = Invoke-Http -Method DELETE -Url "$BaseUrl/admin/agents/$mac" -Headers $authHeaders
Assert-Status -Actual $deleteResp.StatusCode -Expected @(200) -Label "DELETE /admin/agents/{mac}"

Write-Host "[7/7] Verify temporary agent removed"
$finalResp = Invoke-Http -Method GET -Url "$BaseUrl/admin/agents" -Headers $authHeaders
Assert-Status -Actual $finalResp.StatusCode -Expected @(200) -Label "GET /admin/agents after delete"
$finalList = Parse-JsonSafe -Text $finalResp.Content
if ($finalList | Where-Object { $_.macAddress -eq $mac }) {
    throw "Temporary agent still exists after delete"
}

Write-Host "SMOKE TEST PASSED" -ForegroundColor Green

param(
    [string]$BaseUrl = "http://localhost:8888/api/v1",
    [string]$Username = "admin",
    [string]$Password = "password",
    [string]$MacAddress,
    [string]$CommandText,
    [int]$TimeoutSeconds = 90,
    [int]$PollIntervalSeconds = 3,
    [switch]$SkipPoll
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
        $params.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
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

Write-Host "[1/5] Login and get JWT token"
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

Write-Host "[2/5] Select target agent"
$agentsResp = Invoke-Http -Method GET -Url "$BaseUrl/admin/agents" -Headers $authHeaders
Assert-Status -Actual $agentsResp.StatusCode -Expected @(200) -Label "GET /admin/agents"
$agents = Parse-JsonSafe -Text $agentsResp.Content
if ($null -eq $agents) {
    throw "Admin agents response is not JSON"
}

$selectedAgent = $null
if (-not [string]::IsNullOrWhiteSpace($MacAddress)) {
    $selectedAgent = $agents | Where-Object { $_.macAddress -eq $MacAddress } | Select-Object -First 1
    if ($null -eq $selectedAgent) {
        throw "Requested macAddress not found: $MacAddress"
    }
} else {
    $selectedAgent = $agents |
        Where-Object {
            $_.orphan -ne $true -and
            $_.tokenActive -eq $true -and
            $_.status -eq "ONLINE" -and
            -not [string]::IsNullOrWhiteSpace($_.macAddress)
        } |
        Select-Object -First 1
    if ($null -eq $selectedAgent) {
        throw "No ONLINE tokenActive agent found. Use -MacAddress to target a specific PC."
    }
}

$targetMac = [string]$selectedAgent.macAddress
$targetName = [string]$selectedAgent.pcName
Write-Host "Target: $targetName ($targetMac) status=$($selectedAgent.status) ip=$($selectedAgent.ipAddress)"

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$marker = "COMMAND_SMOKE_OK_$stamp"
if ([string]::IsNullOrWhiteSpace($CommandText)) {
    $CommandText = "cmd /c echo $marker"
}
$payloadJson = (@{ cmd = $CommandText } | ConvertTo-Json -Compress)

Write-Host "[3/5] Enqueue RUN_CMD"
$enqueueResp = Invoke-Http -Method POST -Url "$BaseUrl/commands" -Headers $authHeaders -Body @{
    scope = "SINGLE"
    type = "RUN_CMD"
    macAddress = $targetMac
    payloadJson = $payloadJson
}
Assert-Status -Actual $enqueueResp.StatusCode -Expected @(202) -Label "POST /commands"
$enqueueJson = Parse-JsonSafe -Text $enqueueResp.Content
if ($null -eq $enqueueJson) {
    throw "Command enqueue response is not JSON"
}

$taskId = $null
if ($enqueueJson.PSObject.Properties.Name -contains "taskId") {
    $taskId = [long]$enqueueJson.taskId
} elseif (($enqueueJson.PSObject.Properties.Name -contains "taskIds") -and $enqueueJson.taskIds.Count -gt 0) {
    $taskId = [long]$enqueueJson.taskIds[0]
}

if ($null -eq $taskId) {
    throw "Command enqueue response does not contain taskId"
}

Write-Host "Queued taskId=$taskId"

if ($SkipPoll) {
    Write-Host "[4/5] Poll skipped"
    Write-Host "[5/5] Done"
    Write-Host "COMMAND SEND TEST PASSED (queued only)" -ForegroundColor Green
    exit 0
}

Write-Host "[4/5] Poll command status"
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$sawProgress = $false
$lastStatus = "QUEUED"

while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds $PollIntervalSeconds

    $statusResp = Invoke-Http -Method GET -Url "$BaseUrl/commands?taskId=$taskId" -Headers $authHeaders
    Assert-Status -Actual $statusResp.StatusCode -Expected @(200) -Label "GET /commands?taskId"
    $task = Parse-JsonSafe -Text $statusResp.Content
    if ($null -eq $task) {
        throw "Command status response is not JSON"
    }

    $lastStatus = [string]$task.status
    Write-Host "taskId=$taskId status=$lastStatus"

    if ($lastStatus -in @("DELIVERED", "ACKED", "COMPLETED")) {
        $sawProgress = $true
    }

    if ($lastStatus -eq "COMPLETED") {
        $stdout = [string]$task.stdout
        $stderr = [string]$task.stderr
        $exitCode = $task.exitCode
        if ($stdout -match [regex]::Escape($marker) -or -not [string]::IsNullOrWhiteSpace($CommandText)) {
            Write-Host "stdout: $stdout"
            if (-not [string]::IsNullOrWhiteSpace($stderr)) {
                Write-Host "stderr: $stderr"
            }
            Write-Host "[5/5] Done"
            Write-Host "COMMAND SEND TEST PASSED" -ForegroundColor Green
            exit 0
        }
        throw "Command completed but expected marker was not found in stdout. exitCode=$exitCode stdout=$stdout stderr=$stderr"
    }

    if ($lastStatus -eq "FAILED") {
        throw "Command failed. exitCode=$($task.exitCode) stdout=$($task.stdout) stderr=$($task.stderr)"
    }
}

Write-Host "[5/5] Timeout reached"
if ($sawProgress) {
    Write-Host "Transmission verified. Final result was not reported within ${TimeoutSeconds}s." -ForegroundColor Yellow
    Write-Host "Last status: $lastStatus"
    exit 0
}

throw "Command remained in QUEUED state for ${TimeoutSeconds}s. Agent did not pick it up."
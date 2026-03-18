param(
    [string]$BaseUrl = "http://localhost:8080/api/v1"
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

function Invoke-JsonRequest {
    param(
        [string]$Method,
        [string]$Url,
        [object]$Body = $null
    )

    $params = @{
        Uri = $Url
        Method = $Method
        UseBasicParsing = $true
        Headers = @{ "Content-Type" = "application/json" }
    }

    if ($null -ne $Body) {
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

Write-Host "[1/6] dashboards 조회"
$dashboards = Invoke-JsonRequest -Method GET -Url "$BaseUrl/dashboards"
Assert-Status -Actual $dashboards.StatusCode -Expected @(200) -Label "GET /dashboards"

Write-Host "[2/6] 강의실 생성"
$groupName = "smoke-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$newGroup = Invoke-JsonRequest -Method POST -Url "$BaseUrl/groups" -Body @{
    name = $groupName
    description = "smoke test"
}
Assert-Status -Actual $newGroup.StatusCode -Expected @(200, 201) -Label "POST /groups"
$group = $newGroup.Content | ConvertFrom-Json
$groupId = [long]$group.id

Write-Host "[3/6] 강의실 목록 조회"
$classrooms = Invoke-JsonRequest -Method GET -Url "$BaseUrl/dashboard/classrooms"
Assert-Status -Actual $classrooms.StatusCode -Expected @(200) -Label "GET /dashboard/classrooms"
$classroomItems = $classrooms.Content | ConvertFrom-Json
if (-not ($classroomItems | Where-Object { $_.groupId -eq $groupId })) {
    throw "Created classroom not found in dashboard summary list"
}

Write-Host "[4/6] 비연속 대역 등록"
$rangeCreate = Invoke-JsonRequest -Method POST -Url "$BaseUrl/ip-ranges/batch" -Body @{
    groupId = $groupId
    basePrefix = "10.55.1."
    rangeSpec = "10-12,30-31"
    description = "smoke batch"
}
Assert-Status -Actual $rangeCreate.StatusCode -Expected @(200) -Label "POST /ip-ranges/batch"

Write-Host "[5/6] 중복 대역 충돌 확인"
$rangeDup = Invoke-JsonRequest -Method POST -Url "$BaseUrl/ip-ranges/batch" -Body @{
    groupId = $groupId
    basePrefix = "10.55.1."
    rangeSpec = "10-12"
    description = "duplicate"
}
Assert-Status -Actual $rangeDup.StatusCode -Expected @(409) -Label "POST /ip-ranges/batch duplicate"

Write-Host "[6/6] 위젯 API 조회"
$texts = Invoke-JsonRequest -Method GET -Url "$BaseUrl/dashboard/texts"
$images = Invoke-JsonRequest -Method GET -Url "$BaseUrl/dashboard/images"
Assert-Status -Actual $texts.StatusCode -Expected @(200) -Label "GET /dashboard/texts"
Assert-Status -Actual $images.StatusCode -Expected @(200) -Label "GET /dashboard/images"

Write-Host "SMOKE TEST PASSED" -ForegroundColor Green

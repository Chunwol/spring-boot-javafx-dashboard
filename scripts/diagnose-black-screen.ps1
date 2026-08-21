$ErrorActionPreference = 'SilentlyContinue'

function ShortText([object]$Value, [int]$Length = 180) {
    $text = ([string]$Value -replace '[\r\n]+', ' ' -replace '\s{2,}', ' ').Trim()
    if ($text.Length -le $Length) { return $text }
    return $text.Substring(0, $Length)
}

$os = Get-CimInstance Win32_OperatingSystem
$computer = Get-CimInstance Win32_ComputerSystem
$shellKey = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon'
$shellProcesses = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -in @(
        'explorer.exe', 'dwm.exe', 'winlogon.exe', 'userinit.exe',
        'LogonUI.exe', 'ShellExperienceHost.exe', 'StartMenuExperienceHost.exe'
    )
} | Sort-Object SessionId, Name | ForEach-Object {
    [ordered]@{
        name = $_.Name
        pid = $_.ProcessId
        session = $_.SessionId
        path = ShortText $_.ExecutablePath 100
        command = ShortText $_.CommandLine 120
    }
}

$profiles = Get-CimInstance Win32_UserProfile | Where-Object { $_.Loaded -eq $true } |
    Sort-Object SID | ForEach-Object {
        [ordered]@{
            sid = $_.SID
            path = $_.LocalPath
            special = $_.Special
            status = $_.Status
        }
    }

$profileRegistry = Get-ChildItem 'Registry::HKEY_USERS' | Where-Object {
    $_.PSChildName -match '^S-1-5-21-' -and $_.PSChildName -notmatch '_Classes$'
} | ForEach-Object {
    $sid = $_.PSChildName
    $userWinlogon = Get-ItemProperty (
        "Registry::HKEY_USERS\$sid\Software\Microsoft\Windows NT\CurrentVersion\Winlogon")
    [ordered]@{
        sid = $sid
        shell = ShortText $userWinlogon.Shell 100
    }
}

$gpus = Get-CimInstance Win32_VideoController | ForEach-Object {
    [ordered]@{
        name = $_.Name
        status = $_.Status
        driver = $_.DriverVersion
        mode = "{0}x{1}x{2}" -f $_.CurrentHorizontalResolution,
            $_.CurrentVerticalResolution, $_.CurrentBitsPerPixel
        errorCode = $_.ConfigManagerErrorCode
    }
}

$eventStart = (Get-Date).AddHours(-12)
$events = Get-WinEvent -FilterHashtable @{
    LogName = @('System', 'Application')
    StartTime = $eventStart
    Level = @(1, 2, 3)
} -MaxEvents 120 | Where-Object {
    $_.ProviderName -match 'Display|Desktop Window Manager|Dwm|Winlogon|User Profile|Explorer|Shell|Application Error' -or
    $_.Id -in @(14, 41, 1000, 1001, 1002, 4101, 6008)
} | Select-Object -First 12 | ForEach-Object {
    [ordered]@{
        time = $_.TimeCreated.ToString('s')
        log = $_.LogName
        provider = $_.ProviderName
        id = $_.Id
        message = ShortText $_.Message 180
    }
}

$result = [ordered]@{
    collectedAt = (Get-Date).ToString('o')
    identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    boot = $os.LastBootUpTime.ToString('o')
    user = $computer.UserName
    sessions = ShortText ((& quser.exe 2>&1) -join ' | ') 500
    explorerFile = Test-Path "$env:windir\explorer.exe"
    hklmShell = ShortText $shellKey.Shell 100
    hklmUserinit = ShortText $shellKey.Userinit 180
    autoRestartShell = $shellKey.AutoRestartShell
    processes = @($shellProcesses)
    profiles = @($profiles)
    userShells = @($profileRegistry)
    gpu = @($gpus)
    events = @($events)
}

$result | ConvertTo-Json -Depth 7 -Compress


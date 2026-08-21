$ErrorActionPreference = 'Stop'

$current = [Security.Principal.WindowsIdentity]::GetCurrent()
$processes = Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match '^(SCMateClient|java|javaw|ShdCmd)\.exe$' } |
    ForEach-Object {
        $owner = Invoke-CimMethod -InputObject $_ -MethodName GetOwner -ErrorAction SilentlyContinue
        [ordered]@{
            name = $_.Name
            processId = [int]$_.ProcessId
            sessionId = [int]$_.SessionId
            owner = if ($owner.ReturnValue -eq 0) {
                if ($owner.Domain) { "$($owner.Domain)\$($owner.User)" } else { $owner.User }
            } else {
                $null
            }
            executablePath = $_.ExecutablePath
        }
    }

[ordered]@{
    computerName = $env:COMPUTERNAME
    runnerIdentity = $current.Name
    runnerSessionId = [Diagnostics.Process]::GetCurrentProcess().SessionId
    activeConsoleSessionId = [System.Diagnostics.Process]::GetProcesses() |
        Where-Object { $_.ProcessName -eq 'explorer' } |
        Select-Object -First 1 -ExpandProperty SessionId
    processes = @($processes)
} | ConvertTo-Json -Depth 5 -Compress

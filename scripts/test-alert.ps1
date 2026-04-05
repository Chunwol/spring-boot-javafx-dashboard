param(
    [string]$Title = "Remote Command Test",
    [string]$Message = "This PC received the remote test command.",
    [int]$TimeoutSeconds = 10
)

$ErrorActionPreference = "Stop"

try {
    $wshell = New-Object -ComObject WScript.Shell
    $result = $wshell.Popup($Message, $TimeoutSeconds, $Title, 64)
    Write-Output "Alert displayed. Result=$result"
    exit 0
} catch {
    Write-Output "Popup failed: $($_.Exception.Message)"
    Write-Output "$Title - $Message"
    exit 0
}
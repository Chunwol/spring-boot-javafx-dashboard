$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ProcessMemoryReader {
    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr OpenProcess(uint access, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool ReadProcessMemory(
        IntPtr process,
        IntPtr address,
        byte[] buffer,
        int size,
        out IntPtr bytesRead);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool CloseHandle(IntPtr handle);
}
'@

$clientPath = 'C:/Program Files/SCMATE_RX2Client/SCMateClient.exe'
$process = Get-Process -Name 'SCMateClient' -ErrorAction Stop | Select-Object -First 1
$moduleBase = $process.MainModule.BaseAddress
$displayStateAddress = [IntPtr]($moduleBase.ToInt64() + 0x00B0F9C0)
$handle = [ProcessMemoryReader]::OpenProcess(0x0010, $false, $process.Id)
if ($handle -eq [IntPtr]::Zero) {
    throw "OpenProcess failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

try {
    $pointerBuffer = New-Object byte[] 4
    [IntPtr]$bytesRead = [IntPtr]::Zero
    $ok = [ProcessMemoryReader]::ReadProcessMemory(
        $handle,
        $displayStateAddress,
        $pointerBuffer,
        $pointerBuffer.Length,
        [ref]$bytesRead)
    if (-not $ok -or $bytesRead.ToInt64() -ne $pointerBuffer.Length) {
        throw "ReadProcessMemory failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }

    $stringAddress = [IntPtr][BitConverter]::ToUInt32($pointerBuffer, 0)
    $stringBuffer = New-Object byte[] 16
    $ok = [ProcessMemoryReader]::ReadProcessMemory(
        $handle,
        $stringAddress,
        $stringBuffer,
        $stringBuffer.Length,
        [ref]$bytesRead)
    if (-not $ok -or $bytesRead.ToInt64() -lt 2) {
        throw "ReadProcessMemory state string failed: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
    }
    $displayStateText = [Text.Encoding]::Unicode.GetString($stringBuffer).Split([char]0)[0]
    [int]$displayState = 0
    if (-not [int]::TryParse($displayStateText, [ref]$displayState)) {
        throw "Unexpected SCMATE display state: $displayStateText"
    }

    [ordered]@{
        computerName = $env:COMPUTERNAME
        clientVersion = (Get-Item -LiteralPath $clientPath).VersionInfo.ProductVersion
        clientSha256 = (Get-FileHash -LiteralPath $clientPath -Algorithm SHA256).Hash.ToLowerInvariant()
        processId = $process.Id
        moduleBase = ('0x{0:X}' -f $moduleBase.ToInt64())
        displayState = $displayState
    } | ConvertTo-Json -Compress
} finally {
    $null = [ProcessMemoryReader]::CloseHandle($handle)
}

$ErrorActionPreference = 'Stop'

$apiPath = 'C:/Program Files/SCMATE_RX2Client/Shield/ShdApi.dll'
if (-not (Test-Path -LiteralPath $apiPath -PathType Leaf)) {
    throw 'ShdApi.dll not found.'
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ScmateInstallNative {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr LoadLibrary(string path);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern IntPtr GetProcAddress(IntPtr module, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool FreeLibrary(IntPtr module);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int NoArgIntDelegate();
}
'@

function Get-Delegate {
    param(
        [Parameter(Mandatory)] [IntPtr] $Module,
        [Parameter(Mandatory)] [string] $Export
    )

    $address = [ScmateInstallNative]::GetProcAddress($Module, $Export)
    if ($address -eq [IntPtr]::Zero) {
        throw "SCMATE API export not found: $Export"
    }
    [Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer(
        $address,
        [ScmateInstallNative+NoArgIntDelegate]
    )
}

$module = [ScmateInstallNative]::LoadLibrary($apiPath)
if ($module -eq [IntPtr]::Zero) {
    throw "Failed to load ShdApi.dll. Win32Error=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

try {
    $open = Get-Delegate $module '?ShdOpen@@YAJXZ'
    $close = Get-Delegate $module '?ShdClose@@YAJXZ'
    $isUninstalled = Get-Delegate $module '?ShdIsUninstalled@@YAHXZ'

    $uninstalledBeforeOpen = $isUninstalled.Invoke()
    $openResult = $open.Invoke()
    if ($openResult -ne 0) {
        throw ('ShdOpen failed: 0x{0:X8}' -f ([uint32]$openResult))
    }

    try {
        $uninstalledAfterOpen = $isUninstalled.Invoke()
        [ordered]@{
            computerName = $env:COMPUTERNAME
            apiVersion = (Get-Item -LiteralPath $apiPath).VersionInfo.ProductVersion
            shdIsUninstalledBeforeOpen = $uninstalledBeforeOpen
            shdIsUninstalledAfterOpen = $uninstalledAfterOpen
            recoveryModuleInstalled = ($uninstalledBeforeOpen -eq 0)
        } | ConvertTo-Json -Compress
    } finally {
        $null = $close.Invoke()
    }
} finally {
    $null = [ScmateInstallNative]::FreeLibrary($module)
}

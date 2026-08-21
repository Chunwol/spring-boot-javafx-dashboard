$ErrorActionPreference = 'Stop'

$shieldRoot = 'C:/Program Files/SCMATE_RX2Client/Shield'
$apiPath = Join-Path $shieldRoot 'ShdApi.dll'
if (-not (Test-Path -LiteralPath $apiPath -PathType Leaf)) {
    throw 'ShdApi.dll not found.'
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ScmateNative {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr LoadLibrary(string path);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern IntPtr GetProcAddress(IntPtr module, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool FreeLibrary(IntPtr module);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int ShdOpenDelegate();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int ShdCloseDelegate();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate uint ShdGetWorkModeStateDelegate(out uint pendingState);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int ShdIsWriteProtectedDelegate();
}
'@

function Get-ScmateDelegate {
    param(
        [Parameter(Mandatory)] [IntPtr] $Module,
        [Parameter(Mandatory)] [string] $Export,
        [Parameter(Mandatory)] [Type] $DelegateType
    )

    $address = [ScmateNative]::GetProcAddress($Module, $Export)
    if ($address -eq [IntPtr]::Zero) {
        throw "SCMATE API export not found: $Export"
    }
    [Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer($address, $DelegateType)
}

$module = [ScmateNative]::LoadLibrary($apiPath)
if ($module -eq [IntPtr]::Zero) {
    throw "Failed to load ShdApi.dll. Win32Error=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

try {
    $open = Get-ScmateDelegate $module '?ShdOpen@@YAJXZ' ([ScmateNative+ShdOpenDelegate])
    $close = Get-ScmateDelegate $module '?ShdClose@@YAJXZ' ([ScmateNative+ShdCloseDelegate])
    $getState = Get-ScmateDelegate $module '?ShdGetWorkModeState@@YAKPEAK@Z' ([ScmateNative+ShdGetWorkModeStateDelegate])
    $isWriteProtected = Get-ScmateDelegate $module '?ShdIsWriteProtected@@YAHXZ' ([ScmateNative+ShdIsWriteProtectedDelegate])

    $openResult = $open.Invoke()
    if ($openResult -ne 0) {
        throw ('ShdOpen failed: 0x{0:X8}' -f ([uint32]$openResult))
    }

    try {
        [uint32]$pendingState = 0
        [uint32]$workModeState = $getState.Invoke([ref]$pendingState)
        $writeProtected = $isWriteProtected.Invoke()

        [ordered]@{
            computerName = $env:COMPUTERNAME
            apiVersion = (Get-Item -LiteralPath $apiPath).VersionInfo.ProductVersion
            workModeState = $workModeState
            workModeStateHex = ('0x{0:X8}' -f $workModeState)
            pendingState = $pendingState
            pendingStateHex = ('0x{0:X8}' -f $pendingState)
            writeProtected = $writeProtected
        } | ConvertTo-Json -Compress
    } finally {
        $null = $close.Invoke()
    }
} finally {
    $null = [ScmateNative]::FreeLibrary($module)
}

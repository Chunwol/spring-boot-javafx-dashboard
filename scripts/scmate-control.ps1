param(
    [Parameter(Mandatory)]
    [ValidateSet('Status', 'RequestOperator', 'RequestProtected', 'UpdateBaseline')]
    [string] $Action
)

$ErrorActionPreference = 'Stop'

$shieldRoot = 'C:/Program Files/SCMATE_RX2Client/Shield'
$apiPath = Join-Path $shieldRoot 'ShdApi.dll'
if (-not (Test-Path -LiteralPath $apiPath -PathType Leaf)) {
    throw 'ShdApi.dll not found.'
}

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class ScmateControlNative {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr LoadLibrary(string path);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
    public static extern IntPtr GetProcAddress(IntPtr module, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool FreeLibrary(IntPtr module);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int NoArgResultDelegate();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate uint GetWorkModeStateDelegate(out uint pendingState);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate int SetInfoDelegate(uint infoClass, IntPtr value, uint valueSize);
}
'@

function Get-ScmateDelegate {
    param(
        [Parameter(Mandatory)] [IntPtr] $Module,
        [Parameter(Mandatory)] [string] $Export,
        [Parameter(Mandatory)] [Type] $DelegateType
    )

    $address = [ScmateControlNative]::GetProcAddress($Module, $Export)
    if ($address -eq [IntPtr]::Zero) {
        throw "SCMATE API export not found: $Export"
    }
    [Runtime.InteropServices.Marshal]::GetDelegateForFunctionPointer($address, $DelegateType)
}

function ConvertTo-ScmateState {
    param(
        [Parameter(Mandatory)] [uint32] $RawState,
        [Parameter(Mandatory)] [uint32] $PendingState
    )

    $transitionPending = ($RawState -band 0x2) -ne 0
    [ordered]@{
        raw = $RawState
        rawHex = ('0x{0:X8}' -f $RawState)
        mode = if (($RawState -band 0x1) -ne 0) { 'PROTECTED' } else { 'OPERATOR' }
        transitionPending = $transitionPending
        pendingRaw = $PendingState
        pendingRawHex = ('0x{0:X8}' -f $PendingState)
    }
}

$module = [ScmateControlNative]::LoadLibrary($apiPath)
if ($module -eq [IntPtr]::Zero) {
    throw "Failed to load ShdApi.dll. Win32Error=$([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

try {
    $open = Get-ScmateDelegate $module '?ShdOpen@@YAJXZ' ([ScmateControlNative+NoArgResultDelegate])
    $close = Get-ScmateDelegate $module '?ShdClose@@YAJXZ' ([ScmateControlNative+NoArgResultDelegate])
    $getState = Get-ScmateDelegate $module '?ShdGetWorkModeState@@YAKPEAK@Z' ([ScmateControlNative+GetWorkModeStateDelegate])
    $setInfo = Get-ScmateDelegate $module '?ShdSetInfo@@YAJKPEBXK@Z' ([ScmateControlNative+SetInfoDelegate])
    $updateBaseline = Get-ScmateDelegate $module '?ShdUpdateBaseline@@YAJXZ' ([ScmateControlNative+NoArgResultDelegate])

    $openResult = $open.Invoke()
    if ($openResult -ne 0) {
        throw ('ShdOpen failed: 0x{0:X8}' -f ([uint32]$openResult))
    }

    try {
        [uint32]$pendingBefore = 0
        [uint32]$rawBefore = $getState.Invoke([ref]$pendingBefore)
        $before = ConvertTo-ScmateState $rawBefore $pendingBefore
        $apiResult = 0

        switch ($Action) {
            'RequestOperator' {
                if ($before.transitionPending) {
                    throw 'SCMATE mode transition is already pending.'
                }
                if ($before.mode -ne 'OPERATOR') {
                    $value = [Runtime.InteropServices.Marshal]::AllocHGlobal(4)
                    try {
                        [Runtime.InteropServices.Marshal]::WriteInt32($value, 0)
                        $apiResult = $setInfo.Invoke(0x40, $value, 4)
                    } finally {
                        [Runtime.InteropServices.Marshal]::FreeHGlobal($value)
                    }
                }
            }
            'RequestProtected' {
                if ($before.transitionPending) {
                    throw 'SCMATE mode transition is already pending.'
                }
                if ($before.mode -ne 'PROTECTED') {
                    $value = [Runtime.InteropServices.Marshal]::AllocHGlobal(4)
                    try {
                        [Runtime.InteropServices.Marshal]::WriteInt32($value, 1)
                        $apiResult = $setInfo.Invoke(0x40, $value, 4)
                    } finally {
                        [Runtime.InteropServices.Marshal]::FreeHGlobal($value)
                    }
                }
            }
            'UpdateBaseline' {
                if ($before.mode -ne 'OPERATOR' -or $before.transitionPending) {
                    throw 'Baseline update requires stable operator mode after reboot.'
                }
                $apiResult = $updateBaseline.Invoke()
            }
        }

        if ($apiResult -ne 0) {
            throw ('SCMATE API action failed: 0x{0:X8}' -f ([uint32]$apiResult))
        }

        Start-Sleep -Milliseconds 500
        [uint32]$pendingAfter = 0
        [uint32]$rawAfter = $getState.Invoke([ref]$pendingAfter)
        $after = ConvertTo-ScmateState $rawAfter $pendingAfter

        [ordered]@{
            computerName = $env:COMPUTERNAME
            action = $Action
            apiVersion = (Get-Item -LiteralPath $apiPath).VersionInfo.ProductVersion
            before = $before
            after = $after
            apiResult = $apiResult
        } | ConvertTo-Json -Depth 4 -Compress
    } finally {
        $null = $close.Invoke()
    }
} finally {
    $null = [ScmateControlNative]::FreeLibrary($module)
}

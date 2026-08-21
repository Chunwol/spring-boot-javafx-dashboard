$ErrorActionPreference = 'Continue'

$system = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
$bios = Get-CimInstance Win32_BIOS -ErrorAction SilentlyContinue
$driverPath = 'C:/Windows/System32/drivers/iaStorAVC.sys'
$driverFile = Get-Item -LiteralPath $driverPath -ErrorAction SilentlyContinue
$driverVersion = if ($driverFile) { $driverFile.VersionInfo } else { $null }
$signedDrivers = @(Get-CimInstance Win32_PnPSignedDriver -ErrorAction SilentlyContinue |
    Where-Object {
        $_.DriverName -match 'iaStor|storahci|stornvme' -or
        $_.DeviceName -match 'Intel.*(SATA|Storage|RAID|RST)'
    } |
    Select-Object DeviceName, DriverName, DriverVersion, DriverDate, InfName, Manufacturer, IsSigned)
$physicalDisks = @(Get-PhysicalDisk -ErrorAction SilentlyContinue |
    Select-Object FriendlyName, MediaType, HealthStatus, OperationalStatus, Size)
$dirty = (& fsutil.exe dirty query C: 2>&1) -join "`n"

[ordered]@{
    computerName = $env:COMPUTERNAME
    manufacturer = $system.Manufacturer
    model = $system.Model
    biosVersion = $bios.SMBIOSBIOSVersion
    biosReleaseDate = $bios.ReleaseDate
    iaStorAvc = if ($driverFile) {
        [ordered]@{
            path = $driverFile.FullName
            length = $driverFile.Length
            lastWriteTime = $driverFile.LastWriteTime
            productName = $driverVersion.ProductName
            productVersion = $driverVersion.ProductVersion
            fileVersion = $driverVersion.FileVersion
            companyName = $driverVersion.CompanyName
        }
    } else { $null }
    signedStorageDrivers = $signedDrivers
    physicalDisks = $physicalDisks
    volumeDirtyQuery = $dirty
} | ConvertTo-Json -Depth 7 -Compress

$ErrorActionPreference = 'Continue'

$path = 'C:/Windows/System32/drivers/Shieldf.sys'
$file = Get-Item -LiteralPath $path -ErrorAction SilentlyContinue
$version = if ($file) { $file.VersionInfo } else { $null }
$driver = Get-CimInstance Win32_SystemDriver -Filter "Name='Shieldf'" -ErrorAction SilentlyContinue
$service = (& sc.exe qc Shieldf 2>&1) -join "`n"
$filters = (& fltmc.exe filters 2>&1) -join "`n"

[ordered]@{
    computerName = $env:COMPUTERNAME
    exists = [bool]$file
    file = if ($file) {
        [ordered]@{
            path = $file.FullName
            length = $file.Length
            lastWriteTime = $file.LastWriteTime
            productName = $version.ProductName
            productVersion = $version.ProductVersion
            fileDescription = $version.FileDescription
            fileVersion = $version.FileVersion
            companyName = $version.CompanyName
        }
    } else { $null }
    driver = if ($driver) {
        [ordered]@{
            state = $driver.State
            status = $driver.Status
            startMode = $driver.StartMode
            serviceType = $driver.ServiceType
            pathName = $driver.PathName
        }
    } else { $null }
    serviceConfig = $service
    filterRegistration = @($filters -split "`r?`n" | Where-Object { $_ -match 'Shield' })
} | ConvertTo-Json -Depth 6 -Compress

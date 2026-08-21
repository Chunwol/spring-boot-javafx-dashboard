$ErrorActionPreference = 'Stop'

$adapters = @(Get-NetAdapter -Physical -ErrorAction Stop | Where-Object { $_.Status -eq 'Up' })
$results = foreach ($adapter in $adapters) {
    $properties = @(Get-NetAdapterAdvancedProperty -Name $adapter.Name -AllProperties -ErrorAction Stop |
        Where-Object {
            $_.RegistryKeyword -match 'Wake|Wol|Magic|Pattern|EEE|Green|Power' -or
            $_.DisplayName -match 'Wake|WOL|Magic|Pattern|Shutdown|Energy|Green|Power'
        } |
        Select-Object DisplayName, DisplayValue, RegistryKeyword, RegistryValue,
            ValidDisplayValues, ValidRegistryValues)

    [ordered]@{
        name = $adapter.Name
        interfaceDescription = $adapter.InterfaceDescription
        linkSpeed = [string]$adapter.LinkSpeed
        status = [string]$adapter.Status
        properties = $properties
    }
}

[ordered]@{
    computerName = $env:COMPUTERNAME
    adapters = @($results)
} | ConvertTo-Json -Depth 8 -Compress

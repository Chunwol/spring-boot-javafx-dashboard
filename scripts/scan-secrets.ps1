param()
$pattern = 'SECRET|PASSWORD|API_KEY|JWT_SECRET|APIKEY|ACCESS_TOKEN|REFRESH_TOKEN|token|passwd|pwd'
Write-Output "Scanning repository for likely secrets (pattern=$pattern)"
try {
    git --no-pager grep -nE $pattern 2>$null
} catch {
    Get-ChildItem -Recurse -File | Select-String -Pattern $pattern -CaseSensitive:$false | ForEach-Object {
        "$($_.Path):$($_.LineNumber):$($_.Line)"
    }
}

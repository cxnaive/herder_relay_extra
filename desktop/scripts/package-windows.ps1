param([string]$BuildDirectory = 'build/windows-amd64', [string]$OutputDirectory = 'build/artifacts')
$ErrorActionPreference = 'Stop'
$desktopRoot = Split-Path -Parent $PSScriptRoot
Push-Location $desktopRoot
try {
    $source = [System.IO.Path]::GetFullPath((Join-Path $desktopRoot $BuildDirectory))
    $output = [System.IO.Path]::GetFullPath((Join-Path $desktopRoot $OutputDirectory))
    $config = Get-Content -LiteralPath 'mygo.json' -Raw | ConvertFrom-Json
    $revision = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Could not read source revision.' }
    $records = @()
    foreach ($name in @('Herdr Desktop.exe', 'ghostty-vt.dll')) {
        $file = Get-Item -LiteralPath (Join-Path $source $name) -ErrorAction Stop
        $records += [ordered]@{ file = $name; bytes = $file.Length; sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $metadata = [ordered]@{
        schema = 1; version = $config.version; platform = 'windows/amd64'; revision = $revision
        run_id = $env:GITHUB_RUN_ID; event = $env:GITHUB_EVENT_NAME; signed = $false; files = $records
    }
    $metadata | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $source 'BUILD-INFO.json') -Encoding utf8NoBOM
    $hashes = @($records | ForEach-Object { $_.sha256 + '  ' + $_.file })
    $hashes += (Get-FileHash -LiteralPath (Join-Path $source 'BUILD-INFO.json') -Algorithm SHA256).Hash.ToLowerInvariant() + '  BUILD-INFO.json'
    $hashes | Set-Content -LiteralPath (Join-Path $source 'SHA256SUMS.txt') -Encoding utf8NoBOM
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $archiveName = 'Herdr-Desktop-' + $config.version + '-windows-amd64.zip'
    $archive = Join-Path $output $archiveName
    Compress-Archive -LiteralPath @((Join-Path $source 'Herdr Desktop.exe'), (Join-Path $source 'ghostty-vt.dll'), (Join-Path $source 'BUILD-INFO.json'), (Join-Path $source 'SHA256SUMS.txt')) -DestinationPath $archive -Force
    ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $archiveName) | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding utf8NoBOM
    if ($env:GITHUB_STEP_SUMMARY) {
        @("Windows portable build: $archiveName", '', "Source: $revision", '', 'Unzip both the executable and ghostty-vt.dll into the same folder. This preview is unsigned.') | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY
    }
    Get-Item -LiteralPath $archive | Select-Object Name, Length
} finally {
    Pop-Location
}

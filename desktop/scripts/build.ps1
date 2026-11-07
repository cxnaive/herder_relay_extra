param([string]$Platform = 'windows/amd64,darwin/universal')
$ErrorActionPreference = 'Stop'
$desktopRoot = Split-Path -Parent $PSScriptRoot
$bundledGo = Join-Path (Split-Path -Parent $desktopRoot) '.tools/go/bin/go.exe'
$goBinary = if (Test-Path -LiteralPath $bundledGo) { $bundledGo } else { (Get-Command go -ErrorAction Stop).Source }
$previousPath = $env:PATH
Push-Location $desktopRoot
try {
    $env:PATH = (Split-Path -Parent $goBinary) + ';' + $env:PATH
    & $goBinary tool mygo build -platform $Platform -skip-installer -skip-dmg -skip-notarize
    if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed.' }
} finally {
    $env:PATH = $previousPath
    Pop-Location
}

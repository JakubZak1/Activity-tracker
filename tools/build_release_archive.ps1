param(
    [string]$Version = "v1.0.0-thesis"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$releaseRoot = Join-Path $repoRoot "release"
$stagingRoot = Join-Path $releaseRoot "staging-$Version"
$archivePath = Join-Path $releaseRoot "ActivityTracker-$Version.zip"

if (Test-Path $stagingRoot) {
    $resolvedStaging = (Resolve-Path $stagingRoot).Path
    if (-not $resolvedStaging.StartsWith($releaseRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove staging outside release directory: $resolvedStaging"
    }
    Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingRoot -Force | Out-Null

$repositoryArchive = Join-Path $stagingRoot "repository-$Version.zip"
git -C $repoRoot archive --format=zip --output=$repositoryArchive HEAD
if ($LASTEXITCODE -ne 0) { throw "git archive failed" }

$artifactMappings = @(
    @{ Source = "android/app/build/outputs/apk/debug/app-debug.apk"; Destination = "binaries/android/app-debug.apk" },
    @{ Source = ".pio/build/seeed_xiao_nrf52840_sense/firmware.hex"; Destination = "binaries/firmware/main/firmware.hex" },
    @{ Source = ".pio/build/seeed_xiao_nrf52840_sense/firmware.zip"; Destination = "binaries/firmware/main/firmware.zip" },
    @{ Source = ".pio/build/seeed_xiao_nrf52840_sense_formatter/firmware.hex"; Destination = "binaries/firmware/formatter/firmware.hex" },
    @{ Source = ".pio/build/seeed_xiao_nrf52840_sense_formatter/firmware.zip"; Destination = "binaries/firmware/formatter/firmware.zip" },
    @{ Source = "dataset/raw"; Destination = "research/dataset/raw" },
    @{ Source = "dataset/models"; Destination = "research/dataset/models" },
    @{ Source = "dataset/processed"; Destination = "research/dataset/processed" },
    @{ Source = "dataset/results"; Destination = "research/dataset/results" },
    @{ Source = "dataset/curation"; Destination = "research/dataset/curation" },
    @{ Source = "calibration"; Destination = "research/calibration" }
)

foreach ($mapping in $artifactMappings) {
    $source = Join-Path $repoRoot $mapping.Source
    if (-not (Test-Path $source)) { throw "Required release artifact is missing: $source" }
    $destination = Join-Path $stagingRoot $mapping.Destination
    $destinationParent = Split-Path $destination -Parent
    New-Item -ItemType Directory -Path $destinationParent -Force | Out-Null
    Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
}

$manifestPath = Join-Path $stagingRoot "SHA256SUMS.txt"
$manifestLines = Get-ChildItem -LiteralPath $stagingRoot -Recurse -File |
    Where-Object { $_.FullName -ne $manifestPath } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($stagingRoot.Length).TrimStart('\', '/').Replace('\', '/')
        $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
[System.IO.File]::WriteAllLines($manifestPath, $manifestLines, [System.Text.UTF8Encoding]::new($false))

if (Test-Path $archivePath) { Remove-Item -LiteralPath $archivePath -Force }
Compress-Archive -Path (Join-Path $stagingRoot "*") -DestinationPath $archivePath -CompressionLevel Optimal
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText("$archivePath.sha256", "$archiveHash  $([System.IO.Path]::GetFileName($archivePath))`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "Created $archivePath"
Write-Host "SHA256 $archiveHash"

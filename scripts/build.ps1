[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$selectedJavaHome = Resolve-ProjectJava17 -JavaHome $JavaHome

Invoke-ProjectGradle -ProjectRoot $projectRoot -JavaHome $selectedJavaHome -Tasks @('build') -Offline:$Offline

$properties = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') | ForEach-Object {
    if ($_ -match '^([a-z_]+)=(.*)$') {
        $properties[$Matches[1]] = $Matches[2].Trim()
    }
}
$version = $properties['mod_version']
$modId = $properties['mod_id']
if ($version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$' -or $modId -notmatch '^[a-z][a-z0-9_]+$') {
    throw 'Invalid mod_id or mod_version in gradle.properties.'
}
$artifactName = "$modId-$version.jar"
$sourceJar = Join-Path $projectRoot "build/libs/$artifactName"
$archiveDirectory = Join-Path $projectRoot "dist/$version"

# The Gradle test report is the evidence; do not infer a pass from exit code alone.
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build/test-results/test') -Filter 'TEST-*.xml' -File)
$testCount = 0
$failedCount = 0
$skippedCount = 0
foreach ($testFile in $testFiles) {
    [xml]$report = Get-Content -LiteralPath $testFile.FullName -Raw
    $testCount += [int]$report.testsuite.tests
    $failedCount += [int]$report.testsuite.failures + [int]$report.testsuite.errors
    $skippedCount += [int]$report.testsuite.skipped
}
if ($testCount -eq 0 -or $failedCount -ne 0 -or $skippedCount -ne 0) {
    throw 'Release archive requires executed tests with no failures or skipped cases.'
}

New-Item -ItemType Directory -Path $archiveDirectory -Force | Out-Null
$destination = Join-Path $archiveDirectory $artifactName
Copy-Item -LiteralPath $sourceJar -Destination $destination -Force
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
$utf8 = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $archiveDirectory 'SHA256SUMS.txt'), "$hash  $artifactName`n", $utf8)

$verification = [ordered]@{
    schemaVersion = 1
    version = $version
    artifact = $artifactName
    sha256 = $hash
    builtAtUtc = [DateTime]::UtcNow.ToString('o')
    minecraft = $properties['minecraft_version']
    forge = $properties['forge_version']
    javaMajor = 17
    automatedChecks = @('JUnit', 'verifyArchitecture', 'verifyModJar')
    tests = $testCount
    failures = $failedCount
    skipped = $skippedCount
    gameValidation = 'pending'
    baselineGameValidation = '0.1.0 camera and 0.1.5 block selection confirmed by user on 2026-10-03'
}
[System.IO.File]::WriteAllText((Join-Path $archiveDirectory 'verification.json'),
    ($verification | ConvertTo-Json -Depth 4) + "`n", $utf8)
Write-Host "Artifact: $destination"
Write-Host "SHA256: $hash"
Write-Host "Checks passed: $testCount tests, architecture and reobfuscated JAR. Game validation: pending."

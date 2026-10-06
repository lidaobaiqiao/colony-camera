[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$CompilerJar,
    [string]$JavaHome,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$selectedJavaHome = Resolve-ProjectJava17 -JavaHome $JavaHome
$compiler = (Resolve-Path -LiteralPath $CompilerJar).Path
if (![IO.File]::Exists($compiler) -or [IO.Path]::GetExtension($compiler) -ne '.jar') {
    throw 'CompilerJar must point to an Eclipse ECJ batch compiler JAR compatible with Java 17.'
}

Invoke-ProjectGradle -ProjectRoot $projectRoot -JavaHome $selectedJavaHome `
    -Tasks @('exportNullAnalysisClasspath') -Offline:$Offline

$outputDirectory = Join-Path $projectRoot 'build/null-analysis'
$classpath = (Get-Content -LiteralPath (Join-Path $outputDirectory 'compile.classpath') -Raw).Trim()
$argumentsFile = Join-Path $outputDirectory 'compile.args'
$logFile = Join-Path $outputDirectory 'check.log'
$mainFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/main/java') -Filter '*.java' -File -Recurse | Sort-Object FullName)
$testFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/test/java') -Filter '*.java' -File -Recurse | Sort-Object FullName)
$gameTestFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/gameTest/java') -Filter '*.java' -File -Recurse | Sort-Object FullName)
$sourceFiles = $mainFiles + $testFiles + $gameTestFiles
if ($sourceFiles.Count -eq 0 -or [string]::IsNullOrWhiteSpace($classpath)) {
    throw 'Source files and the Gradle compile classpath are required for null analysis.'
}

$java = Join-Path $selectedJavaHome 'bin/java.exe'
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText($logFile, '', $utf8)

function Invoke-NullAnalysis {
    param([string]$Stage, [IO.FileInfo[]]$Sources, [string]$AnalysisClasspath, [string]$Destination = 'none')
    # Response files preserve spaces and the long dependency classpath.
    $compilerArguments = @(
        '-17', '-encoding', 'UTF-8', '-proc:none', '-d', ('"' + $Destination + '"'),
        '-maxProblems', '1000', '-classpath', ('"' + $AnalysisClasspath + '"'),
        '-properties', ('"' + (Join-Path $projectRoot 'config/null-analysis.properties') + '"'),
        '-failOnWarning'
    )
    $compilerArguments += @($Sources | ForEach-Object { '"' + $_.FullName + '"' })
    [IO.File]::WriteAllLines($argumentsFile, $compilerArguments, $utf8)
    $output = @(& $java '-Dfile.encoding=UTF-8' -jar $compiler ("@$argumentsFile") 2>&1)
    $compilerExitCode = $LASTEXITCODE
    if ($output.Count -gt 0) {
        [IO.File]::AppendAllText($logFile, "[$Stage]`n" + ($output -join "`n") + "`n", $utf8)
        $output | ForEach-Object { Write-Host $_ }
    }
    return $compilerExitCode
}

# Fresh binaries expose record/enum contracts that ECJ can miss when every source is in one batch.
# A unique build directory prevents deleted or stale project classes from satisfying references.
$binaryDirectory = Join-Path $outputDirectory ('main-classes-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $binaryDirectory | Out-Null
Write-Host "Null analysis: compile $($mainFiles.Count) main files to fresh binaries."
if ((Invoke-NullAnalysis -Stage 'main sources' -Sources $mainFiles -AnalysisClasspath $classpath -Destination $binaryDirectory) -ne 0) {
    throw "ECJ main source analysis failed. See $logFile"
}
$binaryClasspath = $binaryDirectory + [IO.Path]::PathSeparator + $classpath
$failed = $false
Write-Host 'Null analysis: check each main file against binary dependency contracts.'
foreach ($sourceFile in $mainFiles) {
    if ((Invoke-NullAnalysis -Stage $sourceFile.Name -Sources @($sourceFile) -AnalysisClasspath $binaryClasspath) -ne 0) {
        $failed = $true
    }
}
Write-Host 'Null analysis: check JUnit and GameTest source sets against fresh main binaries.'
foreach ($testSet in @(@{Name = 'JUnit'; Files = $testFiles}, @{Name = 'GameTest'; Files = $gameTestFiles})) {
    if ((Invoke-NullAnalysis -Stage $testSet.Name -Sources $testSet.Files -AnalysisClasspath $binaryClasspath) -ne 0) {
        $failed = $true
    }
}
if ($failed) { throw "ECJ binary contract analysis failed. See $logFile" }
$compilerVersion = @(& $java -jar $compiler -version 2>&1) -join ' '
Write-Host "$compilerVersion"
Write-Host "Null analysis passed: $($sourceFiles.Count) main/JUnit/GameTest source files, including main-file binary checks, no warnings or errors."
Write-Host "Log: $logFile"

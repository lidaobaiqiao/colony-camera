Set-StrictMode -Version Latest

function Resolve-ProjectJava17 {
    param([string]$JavaHome)

    $candidate = $JavaHome
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $env:JAVA_HOME
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($null -ne $compiler) {
            $candidate = Split-Path (Split-Path $compiler.Source -Parent) -Parent
        }
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        throw 'Java 17 JDK is required. Set JAVA_HOME or pass -JavaHome <JDK directory>.'
    }
    $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
    $releaseFile = Join-Path $resolved 'release'
    if (!(Test-Path -LiteralPath (Join-Path $resolved 'bin/javac.exe')) -or
        !(Test-Path -LiteralPath (Join-Path $resolved 'bin/java.exe')) -or
        !(Test-Path -LiteralPath $releaseFile)) {
        throw "Not a complete JDK: $resolved"
    }
    if (!(Get-Content -LiteralPath $releaseFile | Where-Object { $_ -match '^JAVA_VERSION="17(?:\.|"|\+|-)' })) {
        throw "Java 17 is required; selected JDK has another version: $resolved"
    }
    return $resolved
}

function Invoke-ProjectGradle {
    param(
        [Parameter(Mandatory)][string]$ProjectRoot,
        [Parameter(Mandatory)][string]$JavaHome,
        [Parameter(Mandatory)][string[]]$Tasks,
        [switch]$Offline
    )

    $previousJavaHome = $env:JAVA_HOME
    Push-Location -LiteralPath $ProjectRoot
    try {
        $env:JAVA_HOME = $JavaHome
        $arguments = @('--console=plain') + $Tasks
        if ($Offline) {
            $arguments += '--offline'
        }
        & (Join-Path $ProjectRoot 'gradlew.bat') @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        $env:JAVA_HOME = $previousJavaHome
        Pop-Location
    }
}

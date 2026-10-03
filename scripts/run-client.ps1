[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
$projectRoot = Split-Path $PSScriptRoot -Parent
$selectedJavaHome = Resolve-ProjectJava17 -JavaHome $JavaHome
Invoke-ProjectGradle -ProjectRoot $projectRoot -JavaHome $selectedJavaHome -Tasks @('runClient') -Offline:$Offline

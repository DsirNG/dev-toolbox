param(
    [string]$WebStormHome = 'D:\soft\WebStorm 2025.2.6.1',
    [string]$OutputDirectory = "$PSScriptRoot\build"
)

$ErrorActionPreference = 'Stop'
$javacCommand = Get-Command javac -ErrorAction Stop
$classesDirectory = Join-Path $OutputDirectory 'classes'
$pluginDirectory = Join-Path $OutputDirectory 'i18n-translation-helper'
$pluginJar = Join-Path $pluginDirectory 'lib\i18n-translation-helper.jar'
$pluginZip = Join-Path $OutputDirectory 'i18n-translation-helper.zip'

if (-not (Test-Path (Join-Path $WebStormHome 'lib\app.jar'))) {
    throw "WebStorm installation not found at $WebStormHome"
}

Remove-Item -LiteralPath $OutputDirectory -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $classesDirectory, (Split-Path $pluginJar), (Join-Path $pluginDirectory 'META-INF') -Force | Out-Null

$classpath = @(
    (Join-Path $WebStormHome 'lib\*'),
    (Join-Path $WebStormHome 'plugins\vuejs\lib\*'),
    (Join-Path $WebStormHome 'plugins\javascript-plugin\lib\*')
) -join ';'
$sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName
& $javacCommand.Source -encoding UTF-8 -source 17 -target 17 -classpath $classpath -d $classesDirectory $sources
if ($LASTEXITCODE -ne 0) { throw "javac exited with $LASTEXITCODE" }

& (Join-Path (Split-Path $javacCommand.Source) 'jar.exe') --create --file $pluginJar -C $classesDirectory .
if ($LASTEXITCODE -ne 0) { throw "jar exited with $LASTEXITCODE" }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'META-INF\plugin.xml') -Destination (Join-Path $pluginDirectory 'META-INF\plugin.xml')
Compress-Archive -LiteralPath $pluginDirectory -DestinationPath $pluginZip -Force
Write-Output "Built plugin: $pluginZip"

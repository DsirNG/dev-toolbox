$ErrorActionPreference = 'Stop'
$catalogPath = Join-Path $HOME '.agents/plugins/marketplace.json'
$pluginRoot = Join-Path $HOME '.codex/plugins/git-diff'
$catalog = if (Test-Path -LiteralPath $catalogPath) {
    Get-Content -Raw -LiteralPath $catalogPath | ConvertFrom-Json -AsHashtable
} else {
    @{ name = 'personal'; interface = @{ displayName = 'Personal' }; plugins = @() }
}
if (-not $catalog.name -or -not $catalog.Contains('plugins')) { throw 'Unexpected marketplace format.' }
$entry = @{ name = 'git-diff'; source = @{ source = 'local'; path = './.codex/plugins/git-diff' }; policy = @{ installation = 'AVAILABLE'; authentication = 'ON_INSTALL' }; category = 'Productivity' }
$existing = @($catalog.plugins | Where-Object { $_.name -eq $entry.name })
if ($existing.Count -gt 0 -and ($existing.Count -ne 1 -or $existing[0].source.path -ne $entry.source.path)) { throw 'A different git-diff plugin already exists in your marketplace.' }
if ((Test-Path -LiteralPath $pluginRoot) -and $existing.Count -eq 0) { throw 'The plugin destination already exists.' }
New-Item -ItemType Directory -Force $pluginRoot,(Split-Path $catalogPath) | Out-Null
foreach ($item in '.codex-plugin','skills','server.mjs','viewer.html') {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $item) -Destination $pluginRoot -Recurse -Force
}
if ($existing.Count -eq 0) {
    $catalog.plugins = @($catalog.plugins | Where-Object { $null -ne $_ }) + @($entry)
    $catalog | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $catalogPath -Encoding utf8NoBOM
}
Write-Host 'Restart Codex, then open Plugins and install git-diff from your personal marketplace.'

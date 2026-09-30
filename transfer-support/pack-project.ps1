param()
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'transfer'
$archive = Join-Path $output 'ParcelReCode-transfer.tar'
if (Test-Path -LiteralPath $archive) { throw "Archive already exists: $archive. Move it before creating a fresh archive." }
New-Item -ItemType Directory -Path $output -Force | Out-Null
& tar.exe -cf $archive --exclude='./transfer' --exclude='.gradle' --exclude='build' --exclude='.idea' --exclude='.ml-packages' --exclude='__pycache__' --exclude='*.pyc' --exclude='local.properties' -C $root .
if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed; do not use the incomplete archive.' }
$hash = Get-FileHash -LiteralPath $archive -Algorithm SHA256
($hash.Hash + '  ParcelReCode-transfer.tar') | Out-File -LiteralPath ($archive + '.sha256') -Encoding ascii
Write-Host "Ready: $archive"
Write-Host "SHA256: $($hash.Hash)"

# Cutting Edge — packaged app smoke test
#
# Catches exactly the failures that previously reached the user:
#   * an installer whose backend cannot start (empty Python runtime)
#   * a window that renders nothing (black screen)
#   * a missing ffmpeg/ffprobe next to the app
#
# Run against the unpacked build produced by electron-builder:
#   pwsh ce-app/scripts/smoke-test.ps1 -AppDir ce-app/frontend/release/win-unpacked

param(
    [Parameter(Mandatory = $true)][string]$AppDir,
    [int]$TimeoutSeconds = 90,
    [int]$Port = 8742
)

$ErrorActionPreference = 'Stop'
$failures = @()

function Check($name, [scriptblock]$test) {
    Write-Host "→ $name" -NoNewline
    try {
        & $test
        Write-Host "  OK" -ForegroundColor Green
    } catch {
        Write-Host "  FAILED: $($_.Exception.Message)" -ForegroundColor Red
        $script:failures += "$name : $($_.Exception.Message)"
    }
}

$resources = Join-Path $AppDir 'resources'

Check 'app entry file exists' {
    $asar = Join-Path $resources 'app.asar'
    if (-not (Test-Path $asar)) { throw "app.asar missing at $asar" }
}

Check 'renderer bundle is referenced relatively' {
    # absolute /assets/... breaks under file:// and produces a black window
    $tmp = Join-Path $env:TEMP "ce-asar-$(Get-Random)"
    npx --yes @electron/asar extract (Join-Path $resources 'app.asar') $tmp | Out-Null
    $index = Get-Content (Join-Path $tmp 'dist\index.html') -Raw
    if ($index -match 'src="/assets/') { throw 'index.html uses absolute asset paths' }
    if (-not (Test-Path (Join-Path $tmp 'dist-electron\main.js'))) { throw 'dist-electron/main.js missing' }
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Check 'ffmpeg and ffprobe are bundled' {
    foreach ($exe in @('ffmpeg.exe', 'ffprobe.exe')) {
        $p = Join-Path $resources "ffmpeg\$exe"
        if (-not (Test-Path $p)) { throw "$exe missing" }
    }
}

Check 'python runtime is portable' {
    $py = Join-Path $resources 'backend\python\python.exe'
    if (-not (Test-Path $py)) { throw 'python.exe missing' }
    $pth = Get-ChildItem (Join-Path $resources 'backend\python') -Filter 'python*._pth' -ErrorAction SilentlyContinue
    if (-not $pth) { throw 'not an embeddable distribution (a venv would not run on a user machine)' }
}

Check 'backend imports and answers /api/health' {
    $py = Join-Path $resources 'backend\python\python.exe'
    $backend = Join-Path $resources 'backend'
    & $py -c "import fastapi, uvicorn, sqlalchemy, pydantic_settings" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'backend dependencies are missing from the packaged runtime' }

    $proc = Start-Process -FilePath $py -ArgumentList 'run_backend.py' -WorkingDirectory $backend -PassThru -WindowStyle Hidden
    try {
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $ok = $false
        while ((Get-Date) -lt $deadline) {
            try {
                $r = Invoke-RestMethod "http://127.0.0.1:$Port/api/health" -TimeoutSec 3
                if ($r.status -eq 'ok') { $ok = $true; Write-Host " (v$($r.version))" -NoNewline; break }
            } catch { Start-Sleep -Milliseconds 700 }
        }
        if (-not $ok) { throw "backend did not answer on port $Port within $TimeoutSeconds s" }
    } finally {
        if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host "SMOKE TEST FAILED ($($failures.Count))" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  • $_" -ForegroundColor Red }
    exit 1
}
Write-Host 'SMOKE TEST PASSED — the installer is shippable.' -ForegroundColor Green

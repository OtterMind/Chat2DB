param(
    [ValidateSet("start", "restart", "stop", "status")]
    [string]$Action = "status",

    [ValidateSet("all", "frontend", "backend")]
    [string]$Component = "all",

    [string]$Workspace = "E:\workspace\Chat2DB",

    [string]$JavaHome = "D:\tool\Java\jdk-17",

    [string]$YarnCmd = "D:\nvm4w\nodejs\yarn.cmd",

    [switch]$InstallBackendDeps
)

$ErrorActionPreference = "Stop"

$logDir = Join-Path $Workspace "logs"
$frontendLog = Join-Path $logDir "frontend.log"
$backendLog = Join-Path $logDir "backend.log"
$frontendPort = 8000
$backendPort = 10821

function Ensure-LogDir {
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
}

function Stop-Port {
    param([int]$Port)
    Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue |
        ForEach-Object {
            Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
        }
}

function Install-BackendDependencies {
    $env:JAVA_HOME = $JavaHome
    Push-Location (Join-Path $Workspace "chat2db-server")
    try {
        mvn -pl chat2db-server-web/chat2db-server-web-api,chat2db-server-domain/chat2db-server-domain-core -am -DskipTests install
    } finally {
        Pop-Location
    }
}

function Start-Backend {
    Ensure-LogDir
    $command = "Set-Location '$Workspace\chat2db-server'; " +
        "`$env:JAVA_HOME='$JavaHome'; " +
        "mvn spring-boot:run -pl chat2db-server-start *> '$backendLog'"
    Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @("-NoProfile", "-Command", $command)
}

function Start-Frontend {
    Ensure-LogDir
    $command = "Set-Location '$Workspace\chat2db-client'; " +
        "& '$YarnCmd' run start:web *> '$frontendLog'"
    Start-Process powershell.exe -WindowStyle Hidden -PassThru -ArgumentList @("-NoProfile", "-Command", $command)
}

function Show-Status {
    $ports = @($frontendPort, $backendPort)
    Get-NetTCPConnection -LocalPort $ports -State Listen -ErrorAction SilentlyContinue |
        Select-Object LocalAddress, LocalPort, State, OwningProcess

    foreach ($log in @($backendLog, $frontendLog)) {
        if (Test-Path $log) {
            Write-Host ""
            Write-Host "== $log =="
            Get-Content $log -Tail 20
        }
    }
}

if ($Action -in @("restart", "stop")) {
    if ($Component -in @("all", "frontend")) {
        Stop-Port -Port $frontendPort
    }
    if ($Component -in @("all", "backend")) {
        Stop-Port -Port $backendPort
    }
    Start-Sleep -Seconds 2
}

if ($Action -in @("start", "restart")) {
    if (($Component -in @("all", "backend")) -and $InstallBackendDeps) {
        Install-BackendDependencies
    }
    if ($Component -in @("all", "backend")) {
        $backend = Start-Backend
        Write-Host "backendPid=$($backend.Id)"
    }
    if ($Component -in @("all", "frontend")) {
        $frontend = Start-Frontend
        Write-Host "frontendPid=$($frontend.Id)"
    }
    Start-Sleep -Seconds 12
}

Show-Status

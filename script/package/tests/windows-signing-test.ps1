param([string]$WinScpPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$signingScript = Join-Path $PSScriptRoot '../sign_windows_package.ps1'
$testDirectory = Join-Path ([IO.Path]::GetTempPath()) ('signing tests ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testDirectory | Out-Null
$caseState = @{ Mode = 'success'; ScriptFile = ''; SignedFile = ''; SignatureChecks = 0; NativeClient = '' }

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-Command {
    [CmdletBinding()]
    param([string]$Name)
    if ($Name -eq 'WinSCP.com') {
        $client = if ($caseState.NativeClient) { $caseState.NativeClient } else { 'Invoke-TestWinScp' }
        return [pscustomobject]@{ Source = $client }
    }
    Microsoft.PowerShell.Core\Get-Command @PSBoundParameters
}

function Invoke-TestWinScp {
    Assert-True ($args.Count -eq 2 -and $args[0] -eq '/ini=nul') 'WinSCP must receive only initialization and script arguments'
    Assert-True ($args[1].StartsWith('/script=')) 'Commands must not be passed through native argv quoting'
    $caseState.ScriptFile = $args[1].Substring('/script='.Length)
    $bytes = [IO.File]::ReadAllBytes($caseState.ScriptFile)
    Assert-True (($bytes[0..2] -join ',') -eq '239,187,191') 'WinSCP script must carry a UTF-8 BOM for Unicode paths'
    $commands = @(Get-Content -LiteralPath $caseState.ScriptFile)
    $uri = 'scp://' + [Uri]::EscapeDataString($env:WIN_SERVER_USER) + ':' +
        [Uri]::EscapeDataString($env:WIN_SSH_PRIVATE_KEY) + '@' + $env:WIN_SERVER_IP
    $expected = 'open "' + $uri + '" -hostkey="' + $env:HOST_KEY + '" -rawsettings SendBuf=0'
    Assert-True ($commands[2] -ceq $expected) 'The session URL and pinned host key must retain their literal quotes'
    Assert-True (@($commands | Where-Object { $_ -match '^call .*--alg SHA-1 ' }).Count -eq 1) 'SHA-1 signing must run once'
    Assert-True (@($commands | Where-Object { $_ -match '^call .*--alg SHA-256 ' }).Count -eq 1) 'SHA-256 signing must run once'
    Assert-True (@($commands | Where-Object { $_ -like '*signing files 测试*' }).Count -ge 4) 'Unicode remote paths must survive'
    $get = @($commands | Where-Object { $_ -match '^get ' })[0]
    Assert-True ($get -match '^get "[^"]+" "([^"]+)"$') 'Download must target a separate quoted temporary file'
    $caseState.SignedFile = $Matches[1]
    if ($caseState.Mode -eq 'transport-failure') {
        $global:LASTEXITCODE = 1
        return
    }
    [IO.File]::WriteAllText($caseState.SignedFile, 'signed fixture')
    $global:LASTEXITCODE = 0
}

function Get-AuthenticodeSignature {
    param([string]$LiteralPath)
    $caseState.SignatureChecks++
    Assert-True ($LiteralPath -eq $caseState.SignedFile) 'Verify the downloaded file before replacing the installer'
    $status = if ($caseState.Mode -eq 'invalid-signature') {
        [System.Management.Automation.SignatureStatus]::NotSigned
    } else { [System.Management.Automation.SignatureStatus]::Valid }
    return [pscustomobject]@{ Status = $status }
}

$env:WIN_SERVER_IP = '127.0.0.1:1'
$env:WIN_SERVER_USER = 'fixture user'
$env:WIN_SSH_PRIVATE_KEY = 'fixture @ : % " password'
$env:REMOTE_SIGN_PATH = '/tmp/signing files 测试'
$env:REMOTE_SIGN_SCRIPT = '/tmp/signing files 测试/jsign.sh'
$env:HOST_KEY = 'ssh-ed25519 255 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA='

try {
    foreach ($extension in @('.msi', '.exe')) {
        foreach ($mode in @('success', 'transport-failure', 'invalid-signature')) {
            $caseState.Mode = $mode
            $caseState.SignatureChecks = 0
            $package = Join-Path $testDirectory ('package with spaces' + $extension)
            [IO.File]::WriteAllText($package, 'original fixture')
            $failure = $null
            try { & $signingScript -PackagePath $package } catch { $failure = $_ }
            if ($mode -eq 'success') {
                if ($failure) { throw $failure }
                Assert-True ((Get-Content -LiteralPath $package -Raw) -eq 'signed fixture') 'Valid signed file must replace the installer'
            } else {
                Assert-True ($null -ne $failure) "$mode must fail"
                Assert-True ((Get-Content -LiteralPath $package -Raw) -eq 'original fixture') 'Failure must preserve the original installer'
            }
            Assert-True (-not (Test-Path -LiteralPath $caseState.ScriptFile)) 'Credential-bearing script must be removed'
            Assert-True (-not (Test-Path -LiteralPath $caseState.SignedFile)) 'Temporary download must be removed'
            $expectedChecks = if ($mode -eq 'transport-failure') { 0 } else { 1 }
            Assert-True ($caseState.SignatureChecks -eq $expectedChecks) 'Unexpected signature verification count'
        }
    }

    if ($WinScpPath) {
        $caseState.NativeClient = (Resolve-Path -LiteralPath $WinScpPath).Path
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        $accept = $listener.AcceptTcpClientAsync()
        $env:WIN_SERVER_IP = '127.0.0.1:' + $listener.LocalEndpoint.Port
        try {
            # No SSH server or credentials are required: reaching this listener proves the real
            # WinSCP parser accepted the quoted URL rather than resolving an erroneous "\scp" host.
            try { & $signingScript -PackagePath $package } catch { }
            Assert-True $accept.IsCompletedSuccessfully 'Real WinSCP did not connect to the exact loopback endpoint'
            $accept.Result.Dispose()
            Assert-True ((Get-Content -LiteralPath $package -Raw) -eq 'original fixture') 'Failed SSH handshake must preserve the installer'
        } finally { $listener.Stop() }
    }
    Write-Output 'Windows signing tests passed: MSI/EXE, quotes, Unicode paths, failures, signature checks and cleanup.'
    $global:LASTEXITCODE = 0
} finally {
    Remove-Item -LiteralPath $testDirectory -Recurse -Force
}

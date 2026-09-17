param([Parameter(Mandatory)][string]$PackagePath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
foreach ($name in @('WIN_SERVER_IP', 'WIN_SERVER_USER', 'WIN_SSH_PRIVATE_KEY', 'REMOTE_SIGN_PATH', 'REMOTE_SIGN_SCRIPT', 'HOST_KEY')) {
    if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing signing configuration: $name" }
}
$winScp = Get-Command WinSCP.com -ErrorAction SilentlyContinue
if ($winScp) {
    $executable = $winScp.Source
} else {
    $executable = (Get-Item "${env:ProgramFiles(x86)}/WinSCP/WinSCP.com" -ErrorAction Stop).FullName
}
$source = (Resolve-Path -LiteralPath $PackagePath).Path
$operation = [guid]::NewGuid().ToString('N')
$remote = $env:REMOTE_SIGN_PATH.TrimEnd('/') + '/community-' + $operation + [IO.Path]::GetExtension($source)
$signed = Join-Path ([IO.Path]::GetTempPath()) ('community-signed-' + $operation + [IO.Path]::GetExtension($source))
function Quote-WinScp([string]$value) { '"' + $value.Replace('"', '""') + '"' }
function Quote-Remote([string]$value) { "'" + $value.Replace("'", "'\''") + "'" }
$user = [Uri]::EscapeDataString($env:WIN_SERVER_USER)
$password = [Uri]::EscapeDataString($env:WIN_SSH_PRIVATE_KEY)
$session = "scp://${user}:${password}@$env:WIN_SERVER_IP"
$remoteFile = Quote-Remote $remote
$signer = Quote-Remote $env:REMOTE_SIGN_SCRIPT
$commands = @(
    'option batch abort'
    'option confirm off'
    ('open ' + (Quote-WinScp $session) + ' -hostkey=' + (Quote-WinScp $env:HOST_KEY) + ' -rawsettings SendBuf=0')
    ('put ' + (Quote-WinScp $source) + ' ' + (Quote-WinScp $remote))
    ("call $signer --alg SHA-1 $remoteFile")
    ("call $signer --alg SHA-256 $remoteFile")
    ('get ' + (Quote-WinScp $remote) + ' ' + (Quote-WinScp $signed))
    ('rm ' + (Quote-WinScp $remote))
    'exit'
)
$scriptFile = [IO.Path]::GetTempFileName()
try {
    # WinSCP has its own command parser; native argv escaping can corrupt embedded quotes.
    [IO.File]::WriteAllLines($scriptFile, $commands, [Text.UTF8Encoding]::new($true))
    & $executable /ini=nul "/script=$scriptFile"
    if ($LASTEXITCODE -ne 0) { throw 'Remote package signing failed.' }
    $signature = Get-AuthenticodeSignature -LiteralPath $signed
    if ($signature.Status -ne [System.Management.Automation.SignatureStatus]::Valid) {
        throw "Signed package verification failed: $($signature.Status)"
    }
    Move-Item -LiteralPath $signed -Destination $source -Force
} finally {
    Remove-Item -LiteralPath $signed, $scriptFile -Force -ErrorAction SilentlyContinue
}

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ExtensionId
)

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$HostBat = Join-Path $ScriptDir 'reclip-native-host.bat'
$TargetDir = Join-Path $env:LOCALAPPDATA 'Reclip\NativeMessagingHosts'
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

$ManifestPath = Join-Path $TargetDir 'com.kerim.reclip.json'
$Manifest = [ordered]@{
    name = 'com.kerim.reclip'
    description = 'Reclip Native Messaging Host'
    path = $HostBat
    type = 'stdio'
    allowed_origins = @(
        "chrome-extension://$ExtensionId/"
    )
}
$Manifest | ConvertTo-Json -Depth 4 | Set-Content -Path $ManifestPath -Encoding UTF8

$RegKey = 'HKCU:\Software\Google\Chrome\NativeMessagingHosts\com.kerim.reclip'
New-Item -Path $RegKey -Force | Out-Null
Set-ItemProperty -Path $RegKey -Name '(Default)' -Value $ManifestPath

Write-Host "Installed Native Messaging host manifest to $ManifestPath"
Write-Host "Registered registry key $RegKey"

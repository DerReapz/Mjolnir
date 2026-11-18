<#
.SYNOPSIS
  A stand-in phone for testing the Mjolnir desktop app before the Android app exists.

.EXAMPLE
  .\scripts\fake-phone.ps1 -Relay https://my-relay.fly.dev -Pair K7M-2QX
  .\scripts\fake-phone.ps1 -Send https://example.com,https://example.org
  .\scripts\fake-phone.ps1 -SendFile .\report.pdf
  .\scripts\fake-phone.ps1 -SendFolder .\Photos
  .\scripts\fake-phone.ps1 -Inbox

  State (relay, token, paired PC) is kept in fake-phone.json in the current folder.
#>
param(
    [string]$Relay,
    [string]$Pair,
    [string[]]$Send,
    [string[]]$SendFile,
    [string]$SendFolder,
    [switch]$Inbox
)

$ErrorActionPreference = 'Stop'
$StateFile = Join-Path (Get-Location) 'fake-phone.json'
$ChunkBytes = 8MB

function Load-State {
    if (Test-Path $StateFile) { return Get-Content $StateFile -Raw | ConvertFrom-Json }
    return [pscustomobject]@{ relay = $null; token = $null; deviceId = $null; pcId = $null; pcName = $null }
}

function Save-State($s) { $s | ConvertTo-Json | Set-Content -Path $StateFile -Encoding UTF8 }

function Auth($s) { @{ Authorization = "Bearer $($s.token)" } }

function Post-Json($url, $body, $headers) {
    Invoke-RestMethod -Method Post -Uri $url -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 5 -Compress)
}

function Show-Failure($err) {
    $code = $null
    if ($err.Exception.Response) { $code = [int]$err.Exception.Response.StatusCode }
    if ($code) { Write-Host "Relay answered $code" } else { Write-Host "Failed: $($err.Exception.Message)" }
    exit 1
}

function Require-Paired($s) {
    if (-not $s.token -or -not $s.pcId) { Write-Host 'Not paired yet. Run with -Relay <url> -Pair <code> first.'; exit 1 }
}

# Uploads one file in 8 MiB chunks and returns its FileRef.
function Upload-File($s, [string]$path, [string]$relativePath) {
    $size = (Get-Item -LiteralPath $path).Length
    if ($size -eq 0) { Write-Host "Skipping empty file $relativePath"; return $null }
    $session = Post-Json "$($s.relay)/v1/uploads" @{ relativePath = $relativePath; mimeType = 'application/octet-stream'; sizeBytes = $size } (Auth $s)
    $stream = [System.IO.File]::OpenRead((Resolve-Path -LiteralPath $path))
    try {
        $offset = [long]0
        $status = $session
        while ($offset -lt $size) {
            $length = [int][Math]::Min($ChunkBytes, $size - $offset)
            $buffer = New-Object byte[] $length
            $read = 0
            while ($read -lt $length) { $read += $stream.Read($buffer, $read, $length - $read) }
            $headers = Auth $s
            $headers['Content-Range'] = "bytes $offset-$($offset + $length - 1)/$size"
            $status = Invoke-RestMethod -Method Put -Uri "$($s.relay)/v1/uploads/$($session.uploadId)" -Headers $headers `
                -ContentType 'application/octet-stream' -Body $buffer
            $offset = [long]$status.offset
            Write-Host ("  {0}: {1}%" -f $relativePath, [int](100 * $offset / $size))
        }
        return $status.file
    } finally {
        $stream.Dispose()
    }
}

function Send-Files($s, $items) {
    # $items: list of @{ path; rel }
    $ids = @()
    foreach ($item in $items) {
        $ref = Upload-File $s $item.path $item.rel
        if ($ref) { $ids += $ref.id }
    }
    if ($ids.Count -eq 0) { Write-Host 'Nothing to send.'; exit 1 }
    $r = Post-Json "$($s.relay)/v1/send" @{ to = $s.pcId; tabs = @(); fileIds = @($ids) } (Auth $s)
    Write-Host "Sent $($ids.Count) file(s) to $($s.pcName) (delivery $($r.deliveryId))"
}

$state = Load-State

try {
    if ($Pair) {
        if (-not $Relay) { Write-Host '-Pair needs -Relay <url>'; exit 1 }
        $state.relay = $Relay.TrimEnd('/')
        $reg = Post-Json "$($state.relay)/v1/devices" @{ name = 'Fake Phone'; kind = 'PHONE' } @{}
        $state.token = $reg.token
        $state.deviceId = $reg.deviceId
        Save-State $state
        $code = ($Pair -replace '[-\s]', '').ToUpper()
        $claim = Post-Json "$($state.relay)/v1/pairings/claim" @{ code = $code } (Auth $state)
        Write-Host "Waiting for $($claim.owner.name) to accept..."
        do {
            Start-Sleep -Seconds 2
            $status = Invoke-RestMethod -Uri "$($state.relay)/v1/pairings/$($claim.pairingId)" -Headers (Auth $state)
        } while ($status.state -eq 'PENDING' -or $status.state -eq 'CLAIMED')
        Write-Host $status.state
        if ($status.state -eq 'ACCEPTED') {
            $state.pcId = $claim.owner.deviceId
            $state.pcName = $claim.owner.name
            Save-State $state
        }
    }
    elseif ($Send) {
        Require-Paired $state
        $tabs = @($Send | ForEach-Object { @{ url = $_ } })
        $r = Post-Json "$($state.relay)/v1/send" @{ to = $state.pcId; tabs = $tabs } (Auth $state)
        Write-Host "Sent $($tabs.Count) tab(s) to $($state.pcName) (delivery $($r.deliveryId))"
    }
    elseif ($SendFile) {
        Require-Paired $state
        $items = @($SendFile | ForEach-Object { @{ path = $_; rel = (Split-Path -Leaf $_) } })
        Send-Files $state $items
    }
    elseif ($SendFolder) {
        Require-Paired $state
        $root = (Resolve-Path -LiteralPath $SendFolder).Path.TrimEnd('\')
        $name = Split-Path -Leaf $root
        $items = @(Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
            @{ path = $_.FullName; rel = ($name + '/' + $_.FullName.Substring($root.Length + 1).Replace('\', '/')) }
        })
        Send-Files $state $items
    }
    elseif ($Inbox) {
        Require-Paired $state
        $deliveries = @(Invoke-RestMethod -Uri "$($state.relay)/v1/inbox" -Headers (Auth $state))
        if ($deliveries.Count -eq 0) { Write-Host 'Inbox is empty.' }
        $inboxDir = Join-Path (Get-Location) 'inbox'
        foreach ($d in $deliveries) {
            Write-Host "From $($d.from.name):"
            foreach ($t in $d.tabs) { Write-Host "  tab  $($t.url)" }
            foreach ($f in $d.files) {
                $parts = @($f.relativePath -split '[\\/]' | Where-Object { $_ -and $_ -ne '.' -and $_ -ne '..' })
                $target = Join-Path $inboxDir ($parts -join '\')
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                Invoke-WebRequest -Uri "$($state.relay)/v1/files/$($f.id)" -Headers (Auth $state) -OutFile $target -UseBasicParsing
                Write-Host "  file $($f.relativePath) -> $target"
            }
            Invoke-RestMethod -Method Delete -Uri "$($state.relay)/v1/inbox/$($d.id)" -Headers (Auth $state) | Out-Null
        }
    }
    else {
        Get-Help $MyInvocation.MyCommand.Path -Examples
    }
} catch {
    Show-Failure $_
}

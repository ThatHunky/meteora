<#
.SYNOPSIS
    Builds, tests and publishes a Meteora release on GitHub (docs/updater-plan.md, "Release procedure").

.DESCRIPTION
    Run from the repo root in PowerShell, after bumping versionName and versionCode in
    app/build.gradle.kts, committing, and pushing main:

        .\scripts\release.ps1 -NotesFile notes.md            # build, test, publish
        .\scripts\release.ps1 -NotesFile notes.md -DryRun    # everything but the publish

    It refuses when the working tree is dirty, HEAD is not main or not what origin/main points
    at (gh tags origin's main), the tag v<versionName> already exists locally, on origin or as a
    release, or versionCode was not raised since the highest v* tag. Then it builds and tests,
    copies the APK to build/release/meteora-<versionName>.apk and runs
    `gh release create v<versionName> <apk> --target main` (with --prerelease when versionName
    has a pre-release part). It never pushes and never changes signing.
#>
param(
    [string]$NotesFile,
    [string]$Notes,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Stop-Release([string]$why) {
    Write-Host "release: $why" -ForegroundColor Red
    exit 1
}

# Semver order with pre-release identifiers, as in update/Version.kt.
function ConvertTo-Semver([string]$text) {
    $s = $text.Trim() -replace '^v', ''
    $s = ($s -split '\+', 2)[0]
    if ($s -notmatch '^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$') { return $null }
    $pre = if ($Matches[4]) { @($Matches[4] -split '\.') } else { @() }
    [pscustomobject]@{ Core = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3]); Pre = $pre }
}

function Compare-Semver($a, $b) {
    for ($i = 0; $i -lt 3; $i++) {
        if ($a.Core[$i] -ne $b.Core[$i]) { return [Math]::Sign($a.Core[$i] - $b.Core[$i]) }
    }
    if ($a.Pre.Count -eq 0 -and $b.Pre.Count -eq 0) { return 0 }
    if ($a.Pre.Count -eq 0) { return 1 }
    if ($b.Pre.Count -eq 0) { return -1 }
    for ($i = 0; $i -lt [Math]::Min($a.Pre.Count, $b.Pre.Count); $i++) {
        $x = $a.Pre[$i]; $y = $b.Pre[$i]
        $nx = $x -match '^\d+$'; $ny = $y -match '^\d+$'
        if ($nx -and $ny) { $c = [Math]::Sign([decimal]$x - [decimal]$y) }
        elseif ($nx) { $c = -1 }
        elseif ($ny) { $c = 1 }
        else { $c = [Math]::Sign([string]::CompareOrdinal($x, $y)) }
        if ($c -ne 0) { return $c }
    }
    return [Math]::Sign($a.Pre.Count - $b.Pre.Count)
}

function Get-VersionFields([string]$gradle) {
    $code = [regex]::Match($gradle, '(?m)^\s*versionCode\s*=\s*(\d+)')
    $name = [regex]::Match($gradle, '(?m)^\s*versionName\s*=\s*"([^"]+)"')
    if (-not $code.Success -or -not $name.Success) { return $null }
    [pscustomobject]@{ Code = [int]$code.Groups[1].Value; Name = $name.Groups[1].Value }
}

# ---- Local checks first: nothing leaves the PC until these pass ----
if (git status --porcelain) { Stop-Release 'the working tree is not clean; commit or remove the changes first.' }
$branch = git rev-parse --abbrev-ref HEAD
if ($branch -ne 'main') { Stop-Release "HEAD is on '$branch'; releases are made from main." }
if (-not $NotesFile -and -not $Notes) { Stop-Release 'give the release notes with -NotesFile <file> or -Notes <text>.' }
if ($NotesFile -and -not (Test-Path $NotesFile)) { Stop-Release "no notes file at $NotesFile." }

$current = Get-VersionFields (Get-Content app/build.gradle.kts -Raw)
if (-not $current) { Stop-Release 'could not read versionCode and versionName from app/build.gradle.kts.' }
$version = ConvertTo-Semver $current.Name
if (-not $version) { Stop-Release "versionName '$($current.Name)' is not semver." }
$tag = "v$($current.Name)"
if (git tag -l $tag) { Stop-Release "tag $tag already exists locally." }

# ---- GitHub: tags, origin/main, existing release ----
git fetch --quiet --tags origin main
if ($LASTEXITCODE -ne 0) { Stop-Release 'git fetch origin failed.' }
if (git ls-remote --tags origin "refs/tags/$tag") { Stop-Release "tag $tag already exists on origin." }
if ((git rev-parse HEAD) -ne (git rev-parse origin/main)) { Stop-Release 'HEAD is not origin/main: push main first (gh tags the remote main).' }
gh release view $tag *> $null
if ($LASTEXITCODE -eq 0) { Stop-Release "release $tag already exists on GitHub." }

# The highest earlier v* tag and its versionCode.
$last = $null
foreach ($t in @(git tag -l 'v*')) {
    $v = ConvertTo-Semver $t
    if ($v -and (-not $last -or (Compare-Semver $v $last.V) -gt 0)) { $last = [pscustomobject]@{ Tag = $t; V = $v } }
}
if ($last) {
    if ((Compare-Semver $version $last.V) -le 0) { Stop-Release "$tag is not above the last tag $($last.Tag)." }
    $previous = Get-VersionFields ((git show "$($last.Tag):app/build.gradle.kts") -join "`n")
    if (-not $previous) { Stop-Release "could not read versionCode at $($last.Tag)." }
    if ($current.Code -le $previous.Code) { Stop-Release "versionCode $($current.Code) was not raised since $($last.Tag) ($($previous.Code))." }
    Write-Host "release: $($last.Tag) (code $($previous.Code)) -> $tag (code $($current.Code))"
} else {
    Write-Host "release: no earlier v* tag; $tag (code $($current.Code))"
}

# ---- Build and test ----
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --console=plain -q
if ($LASTEXITCODE -ne 0) { Stop-Release 'the build or the tests failed.' }
$built = 'app/build/outputs/apk/debug/app-debug.apk'
if (-not (Test-Path $built)) { Stop-Release "no APK at $built." }
New-Item -ItemType Directory -Force build/release | Out-Null
$apk = "build/release/meteora-$($current.Name).apk"
Copy-Item $built $apk -Force
$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Write-Host "release: $apk, $((Get-Item $apk).Length) bytes, sha256 $hash"

# ---- Publish ----
$title = "$($version.Core -join '.')" + $(if ($version.Pre.Count) { ' ' + ($version.Pre -join ' ') } else { '' })
$ghArgs = @('release', 'create', $tag, $apk, '--target', 'main', '--title', $title)
if ($version.Pre.Count) { $ghArgs += '--prerelease' }
if ($NotesFile) { $ghArgs += @('--notes-file', $NotesFile) } else { $ghArgs += @('--notes', $Notes) }
if ($DryRun) {
    Write-Host "release: dry run, not publishing. Would run: gh $($ghArgs -join ' ')"
    exit 0
}
gh @ghArgs
if ($LASTEXITCODE -ne 0) { Stop-Release 'gh release create failed.' }
Write-Host "release: published $tag"

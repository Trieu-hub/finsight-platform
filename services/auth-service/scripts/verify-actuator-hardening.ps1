<#
.SYNOPSIS
    Procedure A - prove actuator hardening at the actuator layer (not just Spring Security).

.DESCRIPTION
    A valid JWT satisfies `anyRequest().authenticated()`, so the request passes the
    Spring Security filter chain and reaches the actuator handler mapping. Actuator's
    `management.endpoints.web.exposure.include: health,info` then decides the outcome:

        health / info        -> 200  (exposed)
        env, beans, ...       -> 404  (NOT exposed -> hardening confirmed)
                              -> 200  (exposed; only Security was hiding it -> FAIL)

    Without a token these all return 401 from Security, which proves nothing about actuator.

.EXAMPLE
    .\verify-actuator-hardening.ps1 -Email user@finsight.dev -Password 'Secret123!'

.EXAMPLE
    # First-time: create the user, then verify
    .\verify-actuator-hardening.ps1 -Email user@finsight.dev -Password 'Secret123!' -Username tester -Register
#>
[CmdletBinding()]
param(
    [string]$BaseUrl  = 'http://localhost:8081',
    [Parameter(Mandatory = $true)][string]$Email,
    [Parameter(Mandatory = $true)][string]$Password,
    [string]$Username,
    [switch]$Register
)

$ErrorActionPreference = 'Stop'

# Returns the HTTP status code for a request, whether it succeeds (2xx) or fails (4xx/5xx).
function Get-StatusCode {
    param([string]$Url, [hashtable]$Headers = @{})
    try {
        $resp = Invoke-WebRequest -Uri $Url -Headers $Headers -Method Get -UseBasicParsing -ErrorAction Stop
        return [int]$resp.StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode.value__ }
        Write-Warning "No HTTP response from $Url - is the service running on $BaseUrl ? ($($_.Exception.Message))"
        return -1
    }
}

# --- Optional: register the user so login can succeed -------------------------------------
if ($Register) {
    if (-not $Username) { throw "-Register requires -Username." }
    Write-Host "Registering $Email ..." -ForegroundColor Cyan
    $regBody = @{ username = $Username; email = $Email; password = $Password } | ConvertTo-Json
    try {
        Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/register" -Method Post `
            -Body $regBody -ContentType 'application/json' -ErrorAction Stop | Out-Null
        Write-Host "  registered." -ForegroundColor Green
    } catch {
        # 409/400 likely means the user already exists - fine, we'll just log in.
        Write-Host "  register skipped ($($_.Exception.Message))" -ForegroundColor DarkYellow
    }
}

# --- Step 1: log in and capture the access token ------------------------------------------
Write-Host "Logging in as $Email ..." -ForegroundColor Cyan
$loginBody = @{ email = $Email; password = $Password } | ConvertTo-Json
$auth = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post `
    -Body $loginBody -ContentType 'application/json'

if (-not $auth.accessToken) { throw "Login succeeded but no accessToken in response." }
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
Write-Host "  token acquired.`n" -ForegroundColor Green

# --- Step 2: probe each endpoint WITH the token -------------------------------------------
# expected = the code that proves hardening is correct
$targets = @(
    @{ Path = '/actuator/health';      Expected = 200 }
    @{ Path = '/actuator/info';        Expected = 200 }
    @{ Path = '/actuator/env';         Expected = 404 }
    @{ Path = '/actuator/beans';       Expected = 404 }
    @{ Path = '/actuator/configprops'; Expected = 404 }
    @{ Path = '/actuator/mappings';    Expected = 404 }
    @{ Path = '/actuator/threaddump';  Expected = 404 }
    @{ Path = '/actuator/heapdump';    Expected = 404 }
    @{ Path = '/actuator/loggers';     Expected = 404 }
)

$results = foreach ($t in $targets) {
    $code = Get-StatusCode -Url "$BaseUrl$($t.Path)" -Headers $headers
    $verdict = if ($code -eq $t.Expected) { 'PASS' }
               elseif ($code -eq 401)     { 'INCONCLUSIVE (Security blocked - token rejected?)' }
               elseif ($code -eq 200 -and $t.Expected -eq 404) { 'FAIL (exposed! only Security was hiding it)' }
               else { 'FAIL' }
    [pscustomobject]@{
        Endpoint = $t.Path
        Actual   = $code
        Expected = $t.Expected
        Result   = $verdict
    }
}

$results | Format-Table -AutoSize

# --- Step 3: confirm health body has no component details ---------------------------------
Write-Host "`nHealth body (expect status only, NO 'components' key):" -ForegroundColor Cyan
$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Headers $headers
$health | ConvertTo-Json -Depth 5
if ($health.PSObject.Properties.Name -contains 'components') {
    Write-Host "WARNING: 'components' present - show-details/show-components regressed." -ForegroundColor Red
} else {
    Write-Host "OK: no component details leaked." -ForegroundColor Green
}

# --- Summary ------------------------------------------------------------------------------
$failed = @($results | Where-Object { $_.Result -notlike 'PASS*' })
if ($failed.Count -eq 0) {
    Write-Host "`nALL CHECKS PASSED - actuator hardening confirmed at the actuator layer." -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n$($failed.Count) check(s) not passing - review the table above." -ForegroundColor Red
    exit 1
}

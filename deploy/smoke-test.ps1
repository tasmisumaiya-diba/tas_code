param(
    [string] $BaseUri = 'https://localhost:8443',
    [string] $EnvironmentFile = '',
    [string] $CaCertificate = '',
    [switch] $CheckRateLimit
)

$ErrorActionPreference = 'Stop'

if (-not $EnvironmentFile) { $EnvironmentFile = Join-Path $PSScriptRoot '.env.production' }
if (-not (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)) {
    throw "Environment file not found: $EnvironmentFile. Create deploy/.env.production from the example first."
}
if (-not [uri]::IsWellFormedUriString($BaseUri, [UriKind]::Absolute) -or ([uri]$BaseUri).Scheme -ne 'https') {
    throw 'BaseUri must be an absolute HTTPS URL so the bearer token is never sent over plain HTTP.'
}
if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    throw 'curl.exe is required for the smoke check and is included with current Windows versions.'
}

$envText = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $EnvironmentFile))
$tokenMatch = [regex]::Match($envText, '(?m)^CODEBRIDGE_AUTH_TOKEN=([^\r\n]*)')
if (-not $tokenMatch.Success) { throw 'CODEBRIDGE_AUTH_TOKEN was not found in the environment file.' }
$script:CodeBridgeToken = $tokenMatch.Groups[1].Value
if ($script:CodeBridgeToken.Length -lt 16 -or $script:CodeBridgeToken.StartsWith('replace-with-')) {
    throw 'Replace the example CODEBRIDGE_AUTH_TOKEN with a random secret of at least 16 characters before testing.'
}
$script:CodeBridgeBaseUri = $BaseUri.TrimEnd('/')
$script:CodeBridgeCaCertificate = ''
if ($CaCertificate) {
    if (-not (Test-Path -LiteralPath $CaCertificate -PathType Leaf)) { throw "CA certificate not found: $CaCertificate" }
    $script:CodeBridgeCaCertificate = (Resolve-Path -LiteralPath $CaCertificate).Path
}

function Invoke-CodeBridgeRequest {
    param(
        [Parameter(Mandatory)] [string] $Method,
        [Parameter(Mandatory)] [string] $Path,
        [AllowNull()] [string] $Body,
        [switch] $Authenticated
    )

    $curlArguments = @('--silent', '--show-error', '--write-out', "`n%{http_code}", '--request', $Method)
    if ($script:CodeBridgeCaCertificate) { $curlArguments += @('--cacert', $script:CodeBridgeCaCertificate) }
    if ($Body -ne $null) { $curlArguments += @('--header', 'Content-Type: application/json', '--data-binary', $Body) }
    $curlArguments += "$script:CodeBridgeBaseUri$Path"

    if ($Authenticated) {
        $curlArguments += @('--config', '-')
        $curlConfig = 'header = "Authorization: Bearer ' + $script:CodeBridgeToken + '"'
        $rawResponse = ($curlConfig | & curl.exe @curlArguments 2>&1 | Out-String).TrimEnd("`r", "`n")
    } else {
        $rawResponse = (& curl.exe @curlArguments 2>&1 | Out-String).TrimEnd("`r", "`n")
    }
    $curlExitCode = $LASTEXITCODE
    if ($curlExitCode -ne 0) {
        throw "HTTPS request failed (curl exit $curlExitCode). Check the service, certificate trust, and URL."
    }
    $statusSeparator = $rawResponse.LastIndexOf("`n")
    if ($statusSeparator -lt 0) { throw 'curl did not return an HTTP status code.' }
    $statusCode = 0
    if (-not [int]::TryParse($rawResponse.Substring($statusSeparator + 1).Trim(), [ref]$statusCode)) {
        throw 'curl returned an invalid HTTP status code.'
    }
    return [pscustomobject]@{
        StatusCode = $statusCode
        Body = $rawResponse.Substring(0, $statusSeparator).TrimEnd("`r", "`n")
    }
}

$healthResponse = Invoke-CodeBridgeRequest -Method GET -Path '/api/health' -Body $null
if ($healthResponse.StatusCode -ne 200) { throw "Health route returned HTTP $($healthResponse.StatusCode)." }
$health = $healthResponse.Body | ConvertFrom-Json
if ($health.status -ne 'ok' -or -not $health.authenticationRequired) {
    throw 'Health route did not report a healthy server with API authentication enabled.'
}
Write-Host 'PASS: TLS health route is reachable and API authentication is enabled.'

$uiResponse = Invoke-CodeBridgeRequest -Method GET -Path '/' -Body $null
if ($uiResponse.StatusCode -ne 200 -or $uiResponse.Body -notmatch 'id="api-token"') {
    throw 'The UI did not load or does not contain the API token prompt.'
}
Write-Host 'PASS: the UI is served through the TLS proxy.'

$requestBody = '{"from":"c","to":"java","source":"int add(int left, int right) { return left + right; }","validate":true}'
$unauthenticated = Invoke-CodeBridgeRequest -Method POST -Path '/api/convert' -Body $requestBody
if ($unauthenticated.StatusCode -ne 401) { throw "Unauthenticated conversion returned HTTP $($unauthenticated.StatusCode), expected 401." }
Write-Host 'PASS: conversion API rejects requests without the bearer token.'

$conversionResponse = Invoke-CodeBridgeRequest -Method POST -Path '/api/convert' -Body $requestBody -Authenticated
if ($conversionResponse.StatusCode -ne 200) { throw "Authenticated conversion returned HTTP $($conversionResponse.StatusCode)." }
$conversion = $conversionResponse.Body | ConvertFrom-Json
if (-not $conversion.success -or $conversion.validation -ne 'VALID' -or -not $conversion.output) {
    throw "Authenticated conversion or Java compile validation failed: $($conversionResponse.Body)"
}
Write-Host 'PASS: authenticated C-to-Java conversion and Java compile validation succeeded.'

$malformedResponse = Invoke-CodeBridgeRequest -Method POST -Path '/api/convert' -Body '{' -Authenticated
if ($malformedResponse.StatusCode -ne 400) { throw "Malformed JSON returned HTTP $($malformedResponse.StatusCode), expected 400." }
Write-Host 'PASS: malformed JSON receives HTTP 400.'

if ($CheckRateLimit) {
    $rateLimitBody = '{"from":"c","to":"java","source":"int value() { return 1; }"}'
    $rateLimitTriggered = $false
    for ($attempt = 1; $attempt -le 20; $attempt++) {
        $rateResponse = Invoke-CodeBridgeRequest -Method POST -Path '/api/convert' -Body $rateLimitBody -Authenticated
        if ($rateResponse.StatusCode -eq 429) { $rateLimitTriggered = $true; break }
        if ($rateResponse.StatusCode -ne 200) { throw "Rate-limit probe request returned unexpected HTTP $($rateResponse.StatusCode)." }
    }
    if (-not $rateLimitTriggered) { throw 'The conversion rate limit did not trigger within 20 rapid requests.' }
    Write-Host 'PASS: Nginx returned HTTP 429 after the configured burst was exceeded.'
}

Write-Host 'Smoke test complete.'

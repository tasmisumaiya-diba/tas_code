param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $ServerArguments = @()
)

$ErrorActionPreference = 'Stop'
$repo = $PSScriptRoot
$moduleClasses = @(
    'codebridge-web\target\classes',
    'codebridge-core\target\classes',
    'codebridge-frontend-clang\target\classes',
    'codebridge-rules\target\classes',
    'codebridge-validation\target\classes',
    'codebridge-lang-c\target\classes',
    'codebridge-lang-cpp\target\classes',
    'codebridge-lang-java\target\classes'
) | ForEach-Object { Join-Path $repo $_ }

$mavenRepository = Join-Path $env:USERPROFILE '.m2\repository'
$dependencyPaths = @(
    'com\fasterxml\jackson\core\jackson-databind\2.22.3\jackson-databind-2.22.3.jar',
    'com\fasterxml\jackson\core\jackson-core\2.22.3\jackson-core-2.22.3.jar',
    'com\fasterxml\jackson\core\jackson-annotations\2.22\jackson-annotations-2.22.jar',
    'com\fasterxml\jackson\datatype\jackson-datatype-jdk8\2.22.3\jackson-datatype-jdk8-2.22.3.jar'
) | ForEach-Object { Join-Path $mavenRepository $_ }

$missing = @($moduleClasses + $dependencyPaths) | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing.Count -gt 0) {
    throw "CodeBridge runtime files are missing. Run '.\mvnw.cmd -pl codebridge-web -am package' first. Missing: $($missing -join ', ')"
}

$classpath = (@($moduleClasses) + @($dependencyPaths)) -join ';'
& java -cp $classpath com.tascode.codebridge.web.CodeBridgeHttpServer @ServerArguments
exit $LASTEXITCODE

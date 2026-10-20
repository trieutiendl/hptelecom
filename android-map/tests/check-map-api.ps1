param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Platform = 'android-35',
    [string]$Sources = 'android-34'
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($SdkRoot)) { throw 'Pass -SdkRoot with the Android SDK directory.' }
$mapProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$mapOutput = Join-Path $mapProject 'app\build\map-api-check'
$mapJsonOutput = Join-Path $mapOutput 'org-json'
$mapSdkSources = Join-Path $SdkRoot "sources\$Sources\org\json"
$mapAndroidJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"
if (!(Test-Path -LiteralPath $mapSdkSources) -or !(Test-Path -LiteralPath $mapAndroidJar)) {
    throw 'Install Android SDK platform and SDK Sources first, or pass -Platform and -Sources for installed versions.'
}
New-Item -ItemType Directory -Force -Path $mapJsonOutput | Out-Null
foreach ($mapJsonFile in (Get-ChildItem -LiteralPath $mapSdkSources -Filter '*.java')) {
    $mapJsonText = [IO.File]::ReadAllText($mapJsonFile.FullName)
    # Keep the installed Android JSON implementation unchanged except platform
    # annotations that are absent on desktop Java. Never modify SDK source files.
    $mapJsonText = [regex]::Replace($mapJsonText, '(?m)^import (?:static )?(?:android\.|libcore\.).*;\r?\n', '')
    $mapJsonText = [regex]::Replace($mapJsonText, '@(?:UnsupportedAppUsage|NonNull|Nullable|SystemApi)(?:\([^)]*\))?\s*', '')
    [IO.File]::WriteAllText((Join-Path $mapJsonOutput $mapJsonFile.Name), $mapJsonText)
}
$mapJavaSources = @(Get-ChildItem -LiteralPath $mapJsonOutput -Filter '*.java' | Select-Object -ExpandProperty FullName)
$mapJavaSources += (Join-Path $mapProject 'app\src\main\java\vn\hptelecom\map\MapApiClient.java')
$mapJavaSources += (Join-Path $PSScriptRoot 'MapApiClientTest.java')
& javac -encoding UTF-8 -source 17 -target 17 -classpath $mapAndroidJar -d $mapOutput @mapJavaSources
if ($LASTEXITCODE -ne 0) { throw 'Map API regression checks did not compile.' }
& java -classpath "$mapOutput;$mapAndroidJar" vn.hptelecom.map.MapApiClientTest
if ($LASTEXITCODE -ne 0) { throw 'Map API regression checks failed.' }

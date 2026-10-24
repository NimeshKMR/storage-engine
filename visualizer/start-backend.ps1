# Builds the Java engine and starts the Explorer API on 127.0.0.1.
# Never deletes anything. Databases live in $env:EXPLORER_DATA_DIR (default: <project>\explorer-data).
$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Port = if ($env:EXPLORER_PORT) { $env:EXPLORER_PORT } else { "8787" }
$Data = if ($env:EXPLORER_DATA_DIR) { $env:EXPLORER_DATA_DIR } else { Join-Path $Root "explorer-data" }
$Classes = Join-Path $Root "target\classes"
Set-Location $Root

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw "java (JDK 21+) not found on PATH" }

$built = $false
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    Write-Host "Building with Maven..."
    & mvn -q -B compile
    if ($LASTEXITCODE -eq 0) { $built = $true } else { Write-Warning "Maven build failed; falling back to javac." }
}
if (-not $built) {
    if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw "Neither a working Maven nor javac is available" }
    Write-Host "Building with javac into $Classes ..."
    New-Item -ItemType Directory -Force -Path $Classes | Out-Null
    $sources = Get-ChildItem -Path (Join-Path $Root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & javac -nowarn -d $Classes $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed" }
}

Write-Host "Starting Storage Explorer API on http://127.0.0.1:$Port  (data: $Data)"
& java -cp $Classes com.nimeshkmr.storage.explorer.ExplorerServer --port $Port --data-dir $Data

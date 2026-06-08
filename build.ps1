# build.ps1 — compile src/ and package clip-report-mcp.jar
# Adjust $Jdk / $Clip below if your install paths differ.
param(
  [string]$Jdk  = "C:\eGovFrameDev-4.3.1\java\jdk-17.0.2\bin",                       # any JDK 17+
  [string]$Clip = "C:\Program Files (x86)\Clipsoft\CLIP report v5.0\bin\jar"          # CLIP report engine jars
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
$cp = ((Get-ChildItem "$Clip\*.jar").FullName -join ';')
$out = Join-Path $proj "build"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = (Get-ChildItem "$proj\src\*.java").FullName
& "$Jdk\javac.exe" -encoding UTF-8 -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

# jar 파일을 잡고 있는 실행 중 MCP/테스트 java 프로세스 정리 (안 그러면 jar 쓰기가 조용히 실패)
$jarPath = Join-Path $proj "clip-report-mcp.jar"
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*clip-report-mcp.jar*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

& "$Jdk\jar.exe" --create --file "$jarPath" --main-class CrfMcpServer -C $out "."
if ($LASTEXITCODE -ne 0) { throw "jar 생성 실패. clip-report MCP 서버/테스트 java 프로세스가 jar를 잠그고 있을 수 있습니다 — 종료 후 다시 실행하세요." }
Remove-Item $out -Recurse -Force
Write-Output "Built $proj\clip-report-mcp.jar ($([math]::Round((Get-Item "$proj\clip-report-mcp.jar").Length/1KB)) KB)"
Write-Output "Run (stdio):  java -cp `"$Clip\*;$proj\clip-report-mcp.jar`" CrfMcpServer"
Write-Output "Run (http) :  java -cp `"$Clip\*;$proj\clip-report-mcp.jar`" CrfMcpHttp 3333 0.0.0.0"

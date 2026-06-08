# install.ps1 - set up clip-report MCP on THIS machine.
# 받는 사람이 이 폴더를 복사한 뒤 실행하면: Java/CLIP 경로 자동탐지 -> (가능시) 빌드 -> MCP 설정 생성 -> /clipreport 명령 설치.
# 사전 요구: CLIP report v5.0 설치(엔진 jar+라이선스), JRE/JDK 17+.
param(
  [string]$Java = "",
  [string]$Clip = "",
  [ValidateSet("Code","Desktop","Print")] [string]$Target = "Print"
)
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot

function Find-Java {
  $c = @()
  if ($env:JAVA_HOME) { $c += "$env:JAVA_HOME\bin\java.exe" }
  $g = (Get-Command java -ErrorAction SilentlyContinue).Source; if ($g) { $c += $g }
  foreach ($root in @("C:\Program Files\Java","C:\Program Files\Eclipse Adoptium","C:\eGovFrameDev-4.3.1\java","C:\Program Files\Microsoft")) {
    if (Test-Path $root) { Get-ChildItem $root -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $c += $_.FullName } }
  }
  foreach ($j in ($c | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
    try { $v = (& $j -version 2>&1 | Out-String); if ($v -match 'version "(\d+)') { if ([int]$Matches[1] -ge 17) { return $j } } } catch {}
  }
  return $null
}
function Find-Clip {
  foreach ($p in @("C:\Program Files (x86)\Clipsoft","C:\Program Files\Clipsoft","D:\Clipsoft")) {
    if (Test-Path $p) { $v = Get-ChildItem $p -Filter Viewer.jar -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1; if ($v) { return $v.Directory.FullName } }
  }
  return $null
}

if (-not $Java) { $Java = Find-Java }
if (-not $Java) { throw "JDK/JRE 17+ 를 찾지 못했습니다. -Java 'C:\path\to\bin\java.exe' 로 지정하세요." }
if (-not $Clip) { $Clip = Find-Clip }
if (-not $Clip) { throw "CLIP report 엔진(Viewer.jar)을 찾지 못했습니다. -Clip '...\bin\jar' 로 지정하세요." }
Write-Output "Java : $Java"
Write-Output "CLIP : $Clip"

$jar = Join-Path $proj "clip-report-mcp.jar"
$javac = Join-Path (Split-Path $Java) "javac.exe"
if (Test-Path $javac) {
  Write-Output "빌드 중..."
  & "$proj\build.ps1" -Jdk (Split-Path $Java) -Clip $Clip | Out-Null
} elseif (-not (Test-Path $jar)) {
  throw "javac(JDK)도 없고 clip-report-mcp.jar 도 없습니다. JDK로 빌드하거나 jar를 함께 받아오세요."
} else { Write-Output "javac 없음 -> 동봉된 clip-report-mcp.jar 사용" }

$cfg = [ordered]@{ mcpServers = [ordered]@{ "clip-report" = [ordered]@{
  command = $Java
  args = @("-cp", "$Clip\*;$jar", "CrfMcpServer")
} } }
$json = $cfg | ConvertTo-Json -Depth 10
Set-Content -Path (Join-Path $proj "clip-report.mcp.json") -Value $json -Encoding UTF8

$cmdDir = Join-Path $env:USERPROFILE ".claude\commands"
New-Item -ItemType Directory -Force $cmdDir | Out-Null
$cmdN = 0
Get-ChildItem (Join-Path $proj "setup") -Filter "clipreport*.md" | ForEach-Object {
  Copy-Item $_.FullName (Join-Path $cmdDir $_.Name) -Force; $cmdN++
}
Write-Output "/clipreport 명령 설치: $cmdDir ($cmdN 개 파일 — clipreport + 메타 명령들)"

switch ($Target) {
  "Code" {
    $dst = Join-Path $env:USERPROFILE ".mcp.json"
    Set-Content -Path $dst -Value $json -Encoding UTF8
    Write-Output "Claude Code MCP 설정 작성: $dst  (Claude Code 재시작 + 서버 승인)"
  }
  "Desktop" {
    $dst = Join-Path $env:APPDATA "Claude\claude_desktop_config.json"
    New-Item -ItemType Directory -Force (Split-Path $dst) | Out-Null
    if (Test-Path $dst) {
      $existing = Get-Content $dst -Raw | ConvertFrom-Json
      if (-not $existing.mcpServers) { $existing | Add-Member -NotePropertyName mcpServers -NotePropertyValue ([ordered]@{}) }
      $existing.mcpServers | Add-Member -NotePropertyName "clip-report" -NotePropertyValue $cfg.mcpServers["clip-report"] -Force
      ($existing | ConvertTo-Json -Depth 10) | Set-Content $dst -Encoding UTF8
    } else { Set-Content -Path $dst -Value $json -Encoding UTF8 }
    Write-Output "Claude Desktop 설정 갱신: $dst  (Claude Desktop 완전종료 후 재시작)"
  }
  Default {
    Write-Output "`n=== 아래를 Claude 설정에 넣으세요 (또는 -Target Code|Desktop 으로 자동) ==="
    Write-Output $json
  }
}
Write-Output "`n완료. Claude 재시작 후 /mcp 로 clip-report 확인 -> /clipreport <파일> 할말 사용."

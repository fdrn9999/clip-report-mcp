# update.ps1 — clip-report MCP 자가 업데이트(GitHub).
# 하는 일: 실행중 MCP 서버 종료(jar 잠금 해제) -> git pull -> (JDK 있으면)재빌드, 없으면 받은 jar 사용 -> /clipreport 명령 갱신.
# 사용: 이 폴더에서  ./update.ps1   (또는 Claude 에서  /clipreport --update)
param([string]$Java="", [string]$Clip="")
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

# 1) 실행 중인 MCP 서버 종료 (jar 잠금 해제 — 안 그러면 pull/빌드가 조용히 실패)
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*clip-report-mcp.jar*" } |
  ForEach-Object { Write-Output "MCP 서버 종료: PID $($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# 2) git pull (GitHub clone 본에서만 가능)
if (-not (Test-Path (Join-Path $proj ".git"))) {
  throw "git 저장소가 아닙니다 -> 자가 업데이트 불가. GitHub clone 본에서만 됩니다: git clone https://github.com/fdrn9999/clip-report-mcp.git"
}
Write-Output "git pull ..."
git -C $proj pull --ff-only
if ($LASTEXITCODE -ne 0) { throw "git pull 실패(로컬 변경/충돌 가능). 'git -C `"$proj`" status' 로 확인하세요." }

# 3) 재빌드 (JDK 있으면). 없으면 git pull 로 받은 동봉 jar 사용.
if (-not $Java) { $Java = Find-Java }
if (-not $Clip) { $Clip = Find-Clip }
$javac = if ($Java) { Join-Path (Split-Path $Java) "javac.exe" } else { $null }
if ($Java -and $javac -and (Test-Path $javac) -and $Clip) {
  Write-Output "재빌드 ... (Java=$Java, CLIP=$Clip)"
  & "$proj\build.ps1" -Jdk (Split-Path $Java) -Clip $Clip | Out-Null
} else {
  Write-Output "JDK/CLIP 자동탐지 실패 또는 JRE 전용 -> git pull 로 받은 clip-report-mcp.jar 를 그대로 사용."
}

# 4) /clipreport 슬래시 명령 갱신 (설치돼 있을 때만)
$cmd = Join-Path $env:USERPROFILE ".claude\commands\clipreport.md"
if (Test-Path (Split-Path $cmd)) {
  Copy-Item (Join-Path $proj "setup\clipreport.md") $cmd -Force
  Write-Output "/clipreport 명령 갱신: $cmd"
}

$ver = if (Test-Path (Join-Path $proj "VERSION")) { (Get-Content (Join-Path $proj "VERSION") -Raw).Trim() } else { "?" }
Write-Output "`n완료 -> 버전 $ver. Claude 에서 /mcp 로 clip-report 재연결(또는 Claude 재시작)하면 적용됩니다."

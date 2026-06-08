# doctor.ps1 — clip-report MCP "서버 연결 불가" 진단.
# 사용법: 이 폴더(clip-report-mcp)에서  ./doctor.ps1  (필요시 -Java / -Clip / -Tibero 로 경로 지정)
# 하는 일: Java 17+ / CLIP 엔진경로 / json-simple / jar / .env 점검 → 실제 서버 기동 테스트(stderr까지) → 원인 분류 + 등록 명령 출력.
param([string]$Java="", [string]$Clip="", [string]$Tibero="")
$ErrorActionPreference="Continue"
$proj=$PSScriptRoot
function ok($m){ Write-Host "[OK] $m" -ForegroundColor Green }
function bad($m){ Write-Host "[X]  $m" -ForegroundColor Red }
function info($m){ Write-Host "     $m" -ForegroundColor DarkGray }

Write-Host "== clip-report MCP doctor =="
Write-Host "프로젝트 폴더: $proj`n"

# 1) Java 17+ 탐지
if(-not $Java){
  $cands=@()
  if($env:JAVA_HOME){ $cands+="$env:JAVA_HOME\bin\java.exe" }
  $g=(Get-Command java -ErrorAction SilentlyContinue).Source; if($g){ $cands+=$g }
  foreach($root in @("C:\Program Files\Java","C:\Program Files\Eclipse Adoptium","C:\eGovFrameDev-4.3.1\java","C:\Program Files\Microsoft")){
    if(Test-Path $root){ Get-ChildItem $root -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object { $cands+=$_.FullName } }
  }
  foreach($j in ($cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)){
    try{ $v=(& $j -version 2>&1|Out-String); if($v -match 'version "(\d+)'){ if([int]$Matches[1] -ge 17){ $Java=$j; break } } }catch{}
  }
}
if(-not $Java -or -not (Test-Path $Java)){ bad "Java 17+ 를 못 찾음.  -Java 'C:\...\bin\java.exe' 로 지정하세요." }
else{
  $vv=(& $Java -version 2>&1|Out-String); $ver=if($vv -match 'version "([^"]+)"'){$Matches[1]}else{"?"}
  if($vv -match 'version "(\d+)' -and [int]$Matches[1] -ge 17){ ok "Java $ver" ; info $Java }
  else{ bad "Java 버전이 낮음: $ver (17+ 필요)"; info $Java }
}

# 2) CLIP 엔진 폴더(Viewer.jar) + json-simple
if(-not $Clip){
  foreach($p in @("C:\Program Files (x86)\Clipsoft","C:\Program Files\Clipsoft","D:\Clipsoft")){
    if(Test-Path $p){ $v=Get-ChildItem $p -Filter Viewer.jar -Recurse -ErrorAction SilentlyContinue|Select-Object -First 1; if($v){ $Clip=$v.Directory.FullName; break } }
  }
}
if(-not $Clip -or -not (Test-Path $Clip)){ bad "CLIP 엔진(Viewer.jar) 폴더를 못 찾음.  -Clip '...\bin\jar' 로 지정하세요." }
else{
  ok "CLIP 엔진 폴더"; info $Clip
  $js=Get-ChildItem $Clip -Filter "*.jar" -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'json.?simple' }
  if($js){ ok "json-simple 있음: $($js.Name)" }
  else{ bad "json-simple*.jar 가 CLIP 폴더에 없음 → 서버가 부팅 중 죽는 대표 원인(이 설치본에 포함 안 됨)." }
}

# 3) clip-report-mcp.jar
$jar=Join-Path $proj "clip-report-mcp.jar"
if(Test-Path $jar){ ok "clip-report-mcp.jar 있음" } else { bad "clip-report-mcp.jar 없음: $jar" }

# 4) Tibero JDBC (DB 도구 전용 — 연결과는 무관)
if(-not $Tibero){ $Tibero=Join-Path $env:USERPROFILE ".m2\repository\thirdparty\tibero7\1.0\tibero7-1.0.jar" }
if(Test-Path $Tibero){ ok "Tibero JDBC jar 있음 (db_* 도구용)" } else { info "Tibero JDBC jar 없음 → db_* 도구만 불가(연결과 무관): $Tibero" }

# 5) .env (DB 도구 전용 — 연결과는 무관)
$envf=@("$proj\.env","$env:USERPROFILE\.dbtools\.env","$env:USERPROFILE\clip-report-mcp\.env") | Where-Object { Test-Path $_ } | Select-Object -First 1
if($envf){ ok ".env 발견"; info $envf; $keys=(Get-Content $envf|Where-Object{$_ -match '^\s*CLIP_DB_'}|ForEach-Object{($_ -split '=')[0].Trim()}); info ("키: "+($keys -join ', ')) }
else{ info ".env 없음 → db_* 도구만 불가(연결과 무관)" }

# 6) ★ 실제 서버 기동 테스트
Write-Host "`n-- 서버 기동 테스트 (연결 불가의 진짜 원인) --"
if($Java -and (Test-Path $Java) -and (Test-Path $jar) -and $Clip -and (Test-Path $Clip)){
  $cp="$Clip\*;$jar"; if(Test-Path $Tibero){ $cp="$cp;$Tibero" }
  $req='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'+"`n"+'{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
  $errF=Join-Path $env:TEMP "clipmcp_doctor_err.txt"
  $out=$req | & $Java -cp $cp CrfMcpServer 2>$errF
  $blob=[string]($out -join "`n")
  if($blob -match '"inputSchema"'){
    $n=([regex]::Matches($blob,'"inputSchema"')).Count
    ok "서버 정상 기동 — 도구 $($n)개 응답. (연결 가능 상태)"
    info "여기서 OK인데 Claude에서 연결 불가면 → 등록 위치/설정 문제(아래 7번)."
  }
  else{
    bad "서버가 응답하지 않음 → 기동 실패. stderr:"
    if(Test-Path $errF){ Get-Content $errF | Select-Object -First 8 | ForEach-Object { Write-Host "     | $_" -ForegroundColor Yellow } }
    $etxt=if(Test-Path $errF){ Get-Content $errF -Raw } else { "" }
    Write-Host "     ── 해석 ──" -ForegroundColor Yellow
    if($etxt -match 'UnsupportedClassVersion'){ info "→ Java 버전이 낮음(17+ 필요). 17+ java.exe 를 -Java 로 지정." }
    elseif($etxt -match 'org/json/simple|json.simple'){ info "→ json-simple 미발견. -Clip 가 CLIP 'bin\jar' 폴더(Viewer.jar 있는 곳)를 가리키는지 확인." }
    elseif($etxt -match 'Could not find or load main class'){ info "→ classpath/jar 오류. clip-report-mcp.jar 경로 확인." }
    elseif($etxt -match 'NoClassDefFound|ClassNotFound'){ info "→ 필요한 jar 누락. CLIP 설치(bin\jar) 경로 확인." }
    else{ info "→ 위 stderr 첫 줄을 그대로 공유 주세요." }
  }
}
else{ bad "선행 항목(Java/CLIP/jar) 미충족 → 기동 테스트 생략. 위 [X] 항목부터 해결." }

# 7) 등록용 설정 출력 (모든 선행 OK일 때 그대로 사용)
if($Java -and (Test-Path $Java) -and $Clip -and (Test-Path $Clip) -and (Test-Path $jar)){
  $cpFull="$Clip\*;$jar"; if(Test-Path $Tibero){ $cpFull="$cpFull;$Tibero" }
  Write-Host "`n-- Claude Code 등록(사용자 범위: 어느 폴더에서 실행해도 적용) --"
  Write-Host "claude mcp add clip-report -s user -- `"$Java`" -cp `"$cpFull`" CrfMcpServer" -ForegroundColor Cyan
  Write-Host "`n-- 또는 프로젝트 폴더 .mcp.json 에 직접 --"
  $cfg=[ordered]@{ mcpServers=[ordered]@{ "clip-report"=[ordered]@{ command=$Java; args=@("-cp",$cpFull,"CrfMcpServer") } } }
  Write-Host ($cfg|ConvertTo-Json -Depth 9)
  Write-Host "`n→ 등록 후 Claude Code 재시작 → /mcp 에서 clip-report 확인 → /clipreport 사용." -ForegroundColor DarkGray
}

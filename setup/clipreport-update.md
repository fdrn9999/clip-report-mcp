---
description: clip-report MCP 업데이트 (update.ps1: 서버종료→git pull→재빌드→명령갱신) — /clipreport --update 와 동일
---
`/clipreport --update` 과 동일하게 동작한다.

**프로젝트 폴더 찾기**: clip-report MCP 설정 classpath 의 `clip-report-mcp.jar` 경로의 **상위 폴더**가 프로젝트다(Claude Code `~/.mcp.json`, Desktop `%APPDATA%\Claude\claude_desktop_config.json`). 못 찾으면 `~/clip-report-mcp` 시도 → 그래도 없으면 유저에게 폴더를 묻는다.

`<proj>/update.ps1` 을 실행(실행중 서버 종료 → `git pull` → JDK 있으면 재빌드 → `/clipreport` 명령 갱신)하고 출력을 보고한 뒤, 끝에 **"`/mcp` 로 clip-report 재연결(또는 Claude 재시작)하면 적용"** 을 안내한다. (git 저장소가 아니면 update.ps1 이 안내하고 멈춤 → GitHub clone 권장)

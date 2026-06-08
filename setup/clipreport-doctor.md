---
description: clip-report MCP 진단 (doctor.ps1, 서버 연결 안 될 때) — /clipreport --doctor 와 동일
---
`/clipreport --doctor` 과 동일하게 동작한다.

**프로젝트 폴더 찾기**: clip-report MCP 설정 classpath 의 `clip-report-mcp.jar` 경로의 **상위 폴더**가 프로젝트다(Claude Code `~/.mcp.json`, Desktop `%APPDATA%\Claude\claude_desktop_config.json`). 못 찾으면 `~/clip-report-mcp` 시도 → 그래도 없으면 유저에게 폴더를 묻는다.

`<proj>/doctor.ps1` 을 실행하고 진단 결과를 그대로 보고한다(서버 연결 안 될 때).

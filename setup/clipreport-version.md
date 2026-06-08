---
description: clip-report MCP 버전 확인 (설치본 VERSION ↔ GitHub main 비교) — /clipreport --version 과 동일
---
`/clipreport --version` 과 동일하게 동작한다.

**프로젝트 폴더 찾기**: clip-report MCP 설정 classpath 의 `clip-report-mcp.jar` 경로의 **상위 폴더**가 프로젝트다(Claude Code `~/.mcp.json`, Desktop `%APPDATA%\Claude\claude_desktop_config.json`). 못 찾으면 `~/clip-report-mcp` 시도 → 그래도 없으면 유저에게 폴더를 묻는다.

프로젝트 `VERSION` 파일(설치본)과 GitHub `main` 의 VERSION(`https://raw.githubusercontent.com/fdrn9999/clip-report-mcp/main/VERSION`)을 비교해 **현재 / 최신 / 업데이트필요 여부**를 보고한다.

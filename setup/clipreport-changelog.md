---
description: clip-report MCP 변경 이력 (CHANGELOG.md 최신 항목) — /clipreport --changelog 와 동일
---
`/clipreport --changelog` 과 동일하게 동작한다.

**프로젝트 폴더 찾기**: clip-report MCP 설정 classpath 의 `clip-report-mcp.jar` 경로의 **상위 폴더**가 프로젝트다(Claude Code `~/.mcp.json`, Desktop `%APPDATA%\Claude\claude_desktop_config.json`). 못 찾으면 `~/clip-report-mcp` 시도 → 그래도 없으면 유저에게 폴더를 묻는다.

`<proj>/CHANGELOG.md` 의 최신 항목을 읽어 보여준다.

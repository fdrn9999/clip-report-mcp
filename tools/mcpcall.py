#!/usr/bin/env python3
"""clip-report MCP 서버를 stdio JSON-RPC 로 직접 호출하는 하네스 (개발/회귀 테스트용).

사용:
  python tools/mcpcall.py '[["crf_summary",{"path":"C:/x.crf"}]]'
  python tools/mcpcall.py --file calls.json          # 같은 JSON 을 파일로
  python tools/mcpcall.py --list                     # tools/list
JSON 형식: [[tool_name, {arguments}], ...]   (경로는 '/' 구분자 권장)

java / classpath 는 프로젝트의 clip-report.mcp.json(install.ps1 이 생성) 에서 읽고,
없으면 환경변수 CLIP_JAVA / CLIP_CP 를 쓴다.
"""
import sys, os, json, subprocess

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def server_cmd():
    cfg = os.path.join(PROJ, "clip-report.mcp.json")
    if os.path.isfile(cfg):
        with open(cfg, encoding="utf-8-sig") as f:
            s = json.load(f)["mcpServers"]["clip-report"]
        return [s["command"]] + s["args"]
    java, cp = os.environ.get("CLIP_JAVA"), os.environ.get("CLIP_CP")
    if not (java and cp):
        sys.exit("clip-report.mcp.json 이 없고 CLIP_JAVA/CLIP_CP 환경변수도 없습니다.")
    return [java, "-cp", cp, "CrfMcpServer"]


def call(calls, list_tools=False):
    """calls=[[name,args],...] -> list of (name, text, is_error). 서버 1회 기동."""
    lines = [json.dumps({"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {}})]
    if list_tools:
        lines.append(json.dumps({"jsonrpc": "2.0", "id": "L", "method": "tools/list", "params": {}}))
    for i, (t, a) in enumerate(calls, 1):
        lines.append(json.dumps({"jsonrpc": "2.0", "id": i, "method": "tools/call",
                                 "params": {"name": t, "arguments": a}}, ensure_ascii=False))
    env = dict(os.environ, CLIP_MCP_UPDATE_CHECK="0")
    p = subprocess.run(server_cmd(), input=("\n".join(lines) + "\n").encode("utf-8"),
                       capture_output=True, env=env)
    out = {}
    for ln in p.stdout.decode("utf-8", "replace").splitlines():
        try:
            o = json.loads(ln)
        except ValueError:
            continue
        out[o.get("id")] = o
    res = []
    init = out.get(0, {}).get("result", {})
    res.append(("initialize", json.dumps(init.get("serverInfo")), False))
    if list_tools:
        tl = out.get("L", {}).get("result", {}).get("tools", [])
        res.append(("tools/list", "\n".join(t["name"] + " — " + t["description"][:90] for t in tl), False))
    for i, (t, a) in enumerate(calls, 1):
        o = out.get(i)
        if o is None:
            res.append((t, "NO RESPONSE (server died?)", True)); continue
        if "error" in o:
            res.append((t, "RPC ERROR " + json.dumps(o["error"], ensure_ascii=False), True)); continue
        r = o["result"]
        res.append((t, r["content"][0]["text"], bool(r.get("isError"))))
    return res, p.stderr.decode("utf-8", "replace")


def main():
    args = sys.argv[1:]
    list_tools = "--list" in args
    args = [a for a in args if a != "--list"]
    calls = []
    if args and args[0] == "--file":
        with open(args[1], encoding="utf-8") as f:
            calls = json.load(f)
    elif args:
        calls = json.loads(args[0])
    res, err = call(calls, list_tools)
    for name, text, is_err in res:
        print(f"== {name}{' [isError]' if is_err else ''}:\n{text}")
    if err.strip():
        print("--- stderr ---\n" + err[-3000:])


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()

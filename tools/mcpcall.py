#!/usr/bin/env python3
"""clip-report MCP 서버를 stdio JSON-RPC 로 직접 호출하는 하네스 (개발/회귀 테스트용).

사용:
  python tools/mcpcall.py '[["crf_summary",{"path":"C:/x.crf"}]]'
  python tools/mcpcall.py --file calls.json          # 같은 JSON 을 파일로
  python tools/mcpcall.py --list                     # tools/list
  python tools/mcpcall.py --continue-on-error ...    # 실패해도 다음 호출 계속(기본은 첫 isError 에서 중단)
JSON 형식: [[tool_name, {arguments}], ...]   (경로는 '/' 구분자 권장)

기본 동작(stop-on-error): 한 호출이 isError 이면 그 뒤 호출은 보내지 않고 "SKIPPED" 로 표시한다.
  — a/b 파일을 번갈아 쓰는 체인에서 실패한 단계의 출력 파일이 안 써진 채 다음 단계가 한 단계 전 파일을
    읽어 변경이 조용히 유실되는 사고를 막기 위함. (in_place=true 체인에서도 동일하게 유효)

java / classpath 는 프로젝트의 clip-report.mcp.json(install.ps1 이 생성) 에서 읽고,
없으면 환경변수 CLIP_JAVA / CLIP_CP 를 쓴다.
"""
import sys, os, json, subprocess, threading

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


def _rpc(id_, method, params):
    return (json.dumps({"jsonrpc": "2.0", "id": id_, "method": method, "params": params},
                       ensure_ascii=False) + "\n").encode("utf-8")


def call(calls, list_tools=False, stop_on_error=False):
    """calls=[[name,args],...] -> (list of (name, text, is_error), stderr). 서버 1회 기동.
    요청을 하나씩 보내고 응답을 기다리므로 stop_on_error=True 면 첫 isError 뒤 호출은 보내지 않는다(SKIPPED)."""
    env = dict(os.environ, CLIP_MCP_UPDATE_CHECK="0")
    p = subprocess.Popen(server_cmd(), stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.PIPE, env=env)
    err_buf = []
    th = threading.Thread(target=lambda: err_buf.append(p.stderr.read()), daemon=True); th.start()

    def ask(id_, method, params):
        p.stdin.write(_rpc(id_, method, params)); p.stdin.flush()
        while True:
            ln = p.stdout.readline()
            if not ln:
                return None
            try:
                o = json.loads(ln.decode("utf-8", "replace"))
            except ValueError:
                continue
            if o.get("id") == id_:
                return o

    res = []
    try:
        init = ask(0, "initialize", {})
        if not init or "result" not in init:
            res.append(("initialize", "NO RESPONSE / RPC ERROR (server failed to start? rc=%s)" % p.poll(), True))
            stopped = True
        else:
            res.append(("initialize", json.dumps(init["result"].get("serverInfo")), False)); stopped = False
        if list_tools and not stopped:
            lo = ask("L", "tools/list", {})
            if not lo or "result" not in lo:
                res.append(("tools/list", "NO RESPONSE / RPC ERROR", True)); stopped = True
            else:
                tl = lo["result"].get("tools", [])
                res.append(("tools/list", "\n".join(t["name"] + " — " + t["description"][:90] for t in tl), False))
        for i, (t, a) in enumerate(calls, 1):
            if stopped:
                res.append((t, "SKIPPED (이전 호출 실패로 중단 — --continue-on-error 로 계속 가능)", True)); continue
            o = ask(i, "tools/call", {"name": t, "arguments": a})
            if o is None:
                res.append((t, "NO RESPONSE (server died? rc=%s)" % p.poll(), True)); stopped = True; continue
            if "error" in o or not isinstance(o.get("result"), dict):
                res.append((t, "RPC ERROR " + json.dumps(o.get("error", o), ensure_ascii=False), True))
                stopped = stop_on_error; continue
            r = o["result"]
            is_err = bool(r.get("isError"))
            try:
                text = r["content"][0]["text"]
            except (KeyError, IndexError, TypeError):
                text, is_err = "MALFORMED RESULT " + json.dumps(r, ensure_ascii=False)[:500], True
            res.append((t, text, is_err))
            if is_err and stop_on_error:
                stopped = True
    finally:
        try:
            p.stdin.close()
        except Exception:
            pass
        try:
            p.wait(timeout=30)
        except subprocess.TimeoutExpired:
            p.kill(); p.wait(timeout=10)
        th.join(timeout=5)
    err = b"".join(err_buf).decode("utf-8", "replace")
    return res, err


def main():
    args = sys.argv[1:]
    list_tools = "--list" in args
    stop_on_error = "--continue-on-error" not in args
    args = [a for a in args if a not in ("--list", "--continue-on-error", "--stop-on-error")]
    calls = []
    if args and args[0] == "--file":
        with open(args[1], encoding="utf-8") as f:
            calls = json.load(f)
    elif args:
        calls = json.loads(args[0])
    res, err = call(calls, list_tools, stop_on_error)
    for name, text, is_err in res:
        print(f"== {name}{' [isError]' if is_err else ''}:\n{text}")
    if err.strip():
        print("--- stderr ---\n" + err[-3000:])
    if any(is_err for _, _, is_err in res):
        sys.exit(1)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()

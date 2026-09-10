#!/usr/bin/env python3
"""CHANGELOG.md 의 한 버전 항목을 GitHub 릴리스 노트로 뽑는다.

  python tools/release_notes.py 0.10.0            # 본문을 stdout 으로
  python tools/release_notes.py 0.10.0 --title    # 릴리스 제목("v0.10.0 — …")만
  python tools/release_notes.py 0.10.0 --out notes.md

제목은 해당 태그의 커밋 제목("v0.10.0: …")에서 버전 접두어를 뗀 첫 문장(최대 70자)이다.
릴리스 생성 예:
  gh release create v0.10.0 --title "$(python tools/release_notes.py 0.10.0 --title)" --notes-file notes.md
"""
import re, sys, subprocess, os

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FOOTER = "\n\n**설치/업데이트**: `/clipreport --update` (또는 `update.ps1`) → `/mcp` 로 clip-report 재연결. 자세한 절차는 README."


def section(version):
    text = open(os.path.join(PROJ, "CHANGELOG.md"), encoding="utf-8").read()
    m = re.search(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", text, re.S | re.M)
    if not m:
        sys.exit(f"CHANGELOG 에 [{version}] 항목이 없습니다")
    return m.group(1).strip()


def title(version):
    try:
        subj = subprocess.run(["git", "log", "-1", "--format=%s", "v" + version], cwd=PROJ, capture_output=True, text=True, encoding="utf-8").stdout.strip()
    except Exception:
        subj = ""
    subj = re.sub(r"^v?" + re.escape(version) + r"\s*[:：]\s*", "", subj)
    subj = re.split(r"[;；]", subj)[0].strip()
    if len(subj) > 70:
        subj = subj[:70].rstrip("·,—- ") + "…"
    return f"v{version} — {subj}" if subj else f"v{version}"


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    version = args[0].lstrip("v")
    if "--title" in args:
        print(title(version)); return
    body = section(version) + FOOTER
    if "--out" in args:
        out = args[args.index("--out") + 1]
        open(out, "w", encoding="utf-8").write(body); print("wrote", out)
    else:
        print(body)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()

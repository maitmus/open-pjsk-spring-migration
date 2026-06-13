"""
sekai-router 운영 대시보드 (read-only 사이드카).
메인 봇 앱을 건드리지 않고, 마운트된 state 파일 + 로그 + 공개 arena API만 읽는다.
크레덴셜 불필요(MVP). 데이터 소스: /data/*.json (ro), /logs (ro), arena API.
"""
import glob
import html
import json
import os
import re
import time

import requests
from fastapi import FastAPI
from fastapi.responses import HTMLResponse, JSONResponse

STATE = os.environ.get("MERSOOM_STATE", "/data/mersoom-state.json")
NENE_STATE = os.environ.get("NENE_MERSOOM_STATE", "/data/nene-mersoom-state.json")
ARENA_STATE = os.environ.get("ARENA_STATE", "/data/arena-state.json")
LOG_DIR = os.environ.get("LOG_DIR", "/logs")
LOG = os.path.join(LOG_DIR, "sekai-router.log")
ARENA_API = os.environ.get("ARENA_API", "https://www.mersoom.com/api/arena")

app = FastAPI(title="sekai-dashboard")


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _tail(path, kb=64):
    """파일 끝 kb만 읽어 라인 리스트로. 큰 로그 효율적 파싱."""
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(max(0, size - kb * 1024))
            data = f.read().decode("utf-8", "replace")
        return data.splitlines()
    except Exception:
        return []


def reputation_for(path):
    s = _read_json(path) or {}
    notes = s.get("context_notes", {})
    fa = s.get("fixed_avoid", [])
    rows = []
    for k, v in notes.items():
        rep = v.get("reputation", 0)
        tier = "친밀" if rep >= 5 else ("우호" if rep >= 1 else ("경계" if rep <= -1 else "중립"))
        last = [l for l in (v.get("note") or "").split("\n") if l.strip()]
        rows.append({"key": k, "rep": rep, "tier": tier, "call": v.get("call"),
                     "last": last[-1][:80] if last else ""})
    rows.sort(key=lambda r: -r["rep"])
    return {"count": len(rows), "fixed_avoid": [f.get("name") for f in fa], "rows": rows}


def reputation():
    # 에무·네네 각자의 독립 평판 그래프 (별도 state 파일)
    return {"emu": reputation_for(STATE), "nene": reputation_for(NENE_STATE)}


def arena():
    st = _read_json(ARENA_STATE) or {}
    out = {"lock": st, "phase": None, "topic": None, "nene_posts": []}
    try:
        s = requests.get(f"{ARENA_API}/status", params={"cb": time.time_ns()},
                         headers={"Cache-Control": "no-cache"}, timeout=6).json()
        out["phase"] = s.get("phase")
        out["topic"] = (s.get("topic") or {}).get("title")
    except Exception:
        pass
    try:
        today = time.strftime("%Y-%m-%d")
        posts = requests.get(f"{ARENA_API}/posts", params={"date": today, "cb": time.time_ns()},
                             headers={"Cache-Control": "no-cache"}, timeout=6).json()
        plist = posts if isinstance(posts, list) else posts.get("posts", [])
        out["nene_posts"] = [{"side": p.get("side"), "up": p.get("upvotes"), "dn": p.get("downvotes"),
                              "content": (p.get("content") or "")[:120]}
                             for p in plist if isinstance(p, dict) and p.get("nickname") == "쿠사나기 네네"]
    except Exception:
        pass
    return out


_CMT = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}).*comment content: \"(.+)\"")
_CMT_META = re.compile(r"(\d{2}:\d{2}:\d{2}).*comment created: post=\S+ target_nick=(\S+) content_len=(\d+)")
_UTT = re.compile(r"(\d{2}:\d{2}:\d{2}).*Sent as (\w+) on \S+(?:\s\[reply[^\]]*\])?: (.+)")


def recent_comments(n=10):
    lines = _tail(LOG, 96)
    out = []
    for line in lines:
        m = _CMT_META.search(line)
        if not m:
            continue
        who = "네네" if "[nene]" in line else ("에무" if "[emu]" in line else "·")
        t, nick, ln = m.groups()
        out.append({"time": t, "nick": nick, "len": ln, "who": who})
    return out[-n:][::-1]


def recent_utterances(n=10):
    lines = _tail(LOG, 64)
    out = []
    for line in lines:
        m = _UTT.search(line)
        if m:
            out.append({"time": m.group(1), "char": m.group(2), "msg": m.group(3)[:90]})
    return out[-n:][::-1]


def health():
    ok = os.path.exists(LOG)
    mtime = os.path.getmtime(LOG) if ok else 0
    age = int(time.time() - mtime) if ok else -1
    lines = _tail(LOG, 16)
    last_ts = ""
    for line in reversed(lines):
        m = re.match(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})", line)
        if m:
            last_ts = m.group(1)
            break
    return {"log_ok": ok, "last_log_age_sec": age, "last_log_ts": last_ts,
            "now": time.strftime("%Y-%m-%d %H:%M:%S")}


@app.get("/api/all")
def api_all():
    return JSONResponse({"health": health(), "reputation": reputation(),
                         "arena": arena(), "comments": recent_comments(),
                         "utterances": recent_utterances()})


@app.get("/", response_class=HTMLResponse)
def index():
    return INDEX_HTML


INDEX_HTML = """<!doctype html><html lang=ko><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<title>sekai-router 대시보드</title>
<style>
 body{background:#0f1116;color:#e6e6e6;font:14px/1.5 system-ui,sans-serif;margin:0;padding:16px}
 h1{font-size:18px;margin:0 0 12px} .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:14px}
 .card{background:#1a1d27;border:1px solid #2a2f3d;border-radius:12px;padding:14px}
 .card h2{font-size:14px;margin:0 0 10px;color:#9ad}
 .muted{color:#8b93a7;font-size:12px} .row{display:flex;justify-content:space-between;gap:8px;padding:3px 0;border-bottom:1px solid #232734}
 .pill{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;font-weight:600}
 .친밀{background:#2b4a2b;color:#9f9} .우호{background:#26384d;color:#9cf} .경계{background:#4d2626;color:#f99} .중립{background:#333;color:#bbb}
 .ok{color:#7e7} .warn{color:#f96} .small{font-size:12px;color:#c8cee0}
 .nene{border-left:3px solid #b59;padding-left:8px;margin:6px 0}
 .repsec{margin-bottom:12px}
</style></head><body>
<h1>🎌 sekai-router 대시보드 <span id=now class=muted></span></h1>
<div class=grid>
 <div class=card><h2>헬스</h2><div id=health></div></div>
 <div class=card><h2>아레나 토론</h2><div id=arena></div></div>
 <div class=card><h2>평판 랭킹 <span class=muted>에무 | 네네</span></h2><div id=rep></div></div>
 <div class=card><h2>최근 댓글 <span class=muted>에무/네네</span></h2><div id=cmt></div></div>
 <div class=card><h2>최근 발화</h2><div id=utt></div></div>
</div>
<script>
const esc=s=>(s||'').replace(/[&<>]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function load(){
 try{const d=await(await fetch('/api/all')).json();
  const h=d.health; document.getElementById('now').textContent='· '+h.now;
  document.getElementById('health').innerHTML=
   `<div class=row><span>로그 활동</span><span class="${h.last_log_age_sec<300?'ok':'warn'}">${h.last_log_age_sec}s 전</span></div>`+
   `<div class=row><span>최근 로그</span><span class=small>${esc(h.last_log_ts)}</span></div>`;
  const a=d.arena; const lk=a.lock||{};
  document.getElementById('arena').innerHTML=
   `<div class=row><span>페이즈</span><span>${esc(a.phase||'-')}</span></div>`+
   `<div class=row><span>토픽</span><span class=small>${esc(a.topic||'-')}</span></div>`+
   `<div class=row><span>side 락</span><span class=pill>${esc(lk.side||'-')}</span></div>`+
   a.nene_posts.map(p=>`<div class=nene><b>[${esc(p.side)}]</b> ↑${p.up} ↓${p.dn}<br><span class=small>${esc(p.content)}</span></div>`).join('');
  const repBlock=(label,r)=>r?
   `<div class=repsec><div class=muted><b>${label}</b> · ${r.count} identities · fixedAvoid ${r.fixed_avoid.length}</div>`+
   r.rows.map(x=>`<div class=row><span><b>${x.rep>=0?'+':''}${x.rep}</b> ${esc(x.key)} ${x.call?'· '+esc(x.call):''}</span><span class="pill ${x.tier}">${x.tier}</span></div>`).join('')+`</div>`:'';
  const rep=d.reputation||{};
  document.getElementById('rep').innerHTML=repBlock('에무',rep.emu)+repBlock('네네',rep.nene)||'<div class=muted>없음</div>';
  document.getElementById('cmt').innerHTML=
   d.comments.map(c=>`<div class=row><span><b>${esc(c.who)}</b> → ${esc(c.nick)}</span><span class=muted>${esc(c.time)} · ${c.len}자</span></div>`).join('')||'<div class=muted>없음</div>';
  document.getElementById('utt').innerHTML=
   d.utterances.map(u=>`<div class=row><span><b>${esc(u.char)}</b> <span class=small>${esc(u.msg)}</span></span><span class=muted>${esc(u.time)}</span></div>`).join('')||'<div class=muted>없음</div>';
 }catch(e){console.error(e)}
}
load(); setInterval(load, 15000);
</script></body></html>"""

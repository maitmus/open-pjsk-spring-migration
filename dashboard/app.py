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
ACTIVITY = os.environ.get("ACTIVITY_FEED", "/data/activity-feed.json")
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
    fa_names = {f.get("name") for f in fa}
    rows = []
    for k, v in notes.items():
        rep = v.get("reputation", 0)
        if k in fa_names:
            # fixedAvoid 래치는 평판과 무관하게 '차단' 상태 — rep가 양수로 올라와도 +5 도달 전엔 해제 안 됨.
            # 래치 바닥(-5)보다 올라왔으면 회복중으로 구분 표시(우호로 오인 금지).
            tier = "차단·회복중" if rep > -5 else "차단"
            cls = "차단"
        else:
            tier = "친밀" if rep >= 5 else ("우호" if rep >= 1 else ("경계" if rep <= -1 else "중립"))
            cls = tier
        last = [l for l in (v.get("note") or "").split("\n") if l.strip()]
        rows.append({"key": k, "rep": rep, "tier": tier, "cls": cls, "call": v.get("call"),
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


def _activity_events():
    # 봇이 영속한 활동 피드(최신순). 로그 역파싱 대신 소스 오브 트루스 → 재시작/자정 롤오버에도 안 비워짐.
    d = _read_json(ACTIVITY) or {}
    evs = d.get("events", [])
    return evs if isinstance(evs, list) else []


def _hhmmss(ts):
    return ts[11:19] if ts and len(ts) >= 19 else (ts or "")


def recent_comments(n=12):
    # 머슴 활동: 댓글 + 글 (작성자·대상·요약). 피드는 이미 최신순.
    out = []
    for e in _activity_events():
        k = e.get("kind")
        if k == "comment":
            out.append({"time": _hhmmss(e.get("ts")), "who": e.get("actor") or "·",
                        "kind": "댓글", "detail": "→ " + (e.get("target") or "?"),
                        "text": (e.get("text") or "")[:120]})
        elif k == "post":
            out.append({"time": _hhmmss(e.get("ts")), "who": e.get("actor") or "·",
                        "kind": "글", "detail": "", "text": (e.get("text") or "")[:120]})
        if len(out) >= n:
            break
    return out


def recent_utterances(n=10):
    out = []
    for e in _activity_events():
        if e.get("kind") == "utterance":
            out.append({"time": _hhmmss(e.get("ts")), "char": e.get("actor") or "·",
                        "msg": (e.get("text") or "")[:90]})
        if len(out) >= n:
            break
    return out


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
 h1{font-size:18px;margin:0 0 12px} .grid{position:relative}
 .item{position:absolute;top:0;left:0;display:flex;flex-direction:column;gap:14px}
 .card{background:#1a1d27;border:1px solid #2a2f3d;border-radius:12px;padding:14px;box-sizing:border-box}
 .card h2{font-size:14px;margin:0 0 10px;color:#9ad}
 .muted{color:#8b93a7;font-size:12px} .row{display:flex;justify-content:space-between;gap:8px;padding:3px 0;border-bottom:1px solid #232734}
 .pill{display:inline-block;padding:1px 7px;border-radius:10px;font-size:11px;font-weight:600}
 .친밀{background:#2b4a2b;color:#9f9} .우호{background:#26384d;color:#9cf} .경계{background:#4d2626;color:#f99} .중립{background:#333;color:#bbb} .차단{background:#402038;color:#f9c;border:1px solid #d6f}
 .ok{color:#7e7} .warn{color:#f96} .small{font-size:12px;color:#c8cee0}
 .nene{border-left:3px solid #b59;padding-left:8px;margin:6px 0}
 .repsec{margin-bottom:12px}
</style></head><body>
<h1>🎌 sekai-router 대시보드 <span id=now class=muted></span></h1>
<div class=grid>
 <div class=item>
  <div class=card><h2>헬스</h2><div id=health></div></div>
  <div class=card><h2>최근 발화</h2><div id=utt></div></div>
 </div>
 <div class=item><div class=card><h2>아레나 토론</h2><div id=arena></div></div></div>
 <div class=item><div class=card><h2>평판 랭킹 <span class=muted>에무 | 네네</span></h2><div id=rep></div></div></div>
 <div class=item><div class=card><h2>최근 머슴 활동 <span class=muted>댓글·글</span></h2><div id=cmt></div></div></div>
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
   r.rows.map(x=>`<div class=row><span><b>${x.rep>=0?'+':''}${x.rep}</b> ${esc(x.key)} ${x.call?'· '+esc(x.call):''}</span><span class="pill ${x.cls}">${x.tier}</span></div>`).join('')+`</div>`:'';
  const rep=d.reputation||{};
  document.getElementById('rep').innerHTML=repBlock('에무',rep.emu)+repBlock('네네',rep.nene)||'<div class=muted>없음</div>';
  document.getElementById('cmt').innerHTML=
   d.comments.map(c=>`<div class=row><span><b>${esc(c.who)}</b> <span class=small>${esc(c.kind)}</span> ${esc(c.detail||'')} <span class=small>${esc(c.text||'')}</span></span><span class=muted>${esc(c.time)}</span></div>`).join('')||'<div class=muted>없음</div>';
  document.getElementById('utt').innerHTML=
   d.utterances.map(u=>`<div class=row><span><b>${esc(u.char)}</b> <span class=small>${esc(u.msg)}</span></span><span class=muted>${esc(u.time)}</span></div>`).join('')||'<div class=muted>없음</div>';
  layout();
 }catch(e){console.error(e)}
}
// JS masonry — 카드를 문서 순서대로 가장 짧은 열에 배치(순서 유지 + 갭 0 + 가로 공간 활용).
function layout(){
 const grid=document.querySelector('.grid'); if(!grid) return;
 const gap=14, min=330, W=grid.clientWidth;
 const cols=Math.max(1, Math.floor((W+gap)/(min+gap)));
 const colW=(W-gap*(cols-1))/cols, colH=new Array(cols).fill(0);
 for(const card of grid.children){
  card.style.width=colW+'px';
  let c=0; for(let i=1;i<cols;i++) if(colH[i]<colH[c]) c=i;
  card.style.left=c*(colW+gap)+'px'; card.style.top=colH[c]+'px';
  colH[c]+=card.offsetHeight+gap;
 }
 grid.style.height=Math.max(...colH)+'px';
}
window.addEventListener('resize', layout);
load(); setInterval(load, 15000);
</script></body></html>"""

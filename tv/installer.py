#!/usr/bin/env python3
"""Merk Kiosk installer: a small window (in the web browser) that finds the TVs on this Wi-Fi and
installs the kiosk on one with a single button.

    python3 ~/Sites/tv-kiosk/tv/installer.py      (opens http://127.0.0.1:8765 by itself)

Finding TVs is gentle: it only listens to what Android TVs announce on the network (mDNS,
"_androidtvremote2._tcp", the same thing the Google TV remote app uses), then checks port 5555 on
those few addresses. No address range is scanned. Installing runs provision.sh, Undo runs undo.sh.
"""
import html
import json
import os
import re
import socket
import subprocess
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8765
jobs = {}          # ip -> {"name", "lines": [...], "running": bool}
lock = threading.Lock()


def _run_for(cmd, seconds):
    """Runs a command that never ends by itself (dns-sd) for a few seconds and returns its output."""
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    time.sleep(seconds)
    p.terminate()
    try:
        out, _ = p.communicate(timeout=2)
    except subprocess.TimeoutExpired:
        p.kill()
        out, _ = p.communicate()
    return out or ""


def _port_open(ip, port, timeout=1.5):
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def find_tvs():
    """Android TVs announcing themselves on this network: [{name, host, ip, adb}]."""
    names = []
    for line in _run_for(["dns-sd", "-B", "_androidtvremote2._tcp", "local."], 4).splitlines():
        m = re.search(r"\s(Add)\s+\d+\s+\d+\s+\S+\s+_androidtvremote2\._tcp\.\s+(.+)$", line)
        if m and m.group(2).strip() not in names:
            names.append(m.group(2).strip())
    tvs = []
    for name in names:
        out = _run_for(["dns-sd", "-L", name, "_androidtvremote2._tcp", "local."], 2)
        m = re.search(r"can be reached at (\S+?)\.?:(\d+)", out)
        if not m:
            continue
        host = m.group(1)
        out = _run_for(["dns-sd", "-G", "v4", host], 2)
        m = re.search(r"\s(\d+\.\d+\.\d+\.\d+)\s", out)
        if not m:
            continue
        ip = m.group(1)
        if any(t["ip"] == ip for t in tvs):
            continue
        tvs.append({"name": name.replace("\\032", " "), "host": host, "ip": ip, "adb": _port_open(ip, 5555)})
    return tvs


def tv_model(ip):
    """Model and Android version over ADB (the TV asks 'Allow USB debugging?' the first time)."""
    env = dict(os.environ, TV_IP=ip, TVADB_TRIES="1")
    try:
        out = subprocess.run([os.path.join(HERE, "adb.sh"), "shell",
                              "getprop ro.product.model; getprop ro.build.version.release; pm list packages sk.merk.kiosk"],
                             env=env, capture_output=True, text=True, timeout=60).stdout
    except subprocess.TimeoutExpired:
        return None
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    if len(lines) < 2:
        return None
    return {"model": lines[0], "android": lines[1], "kiosk": any("sk.merk.kiosk" in l for l in lines)}


def start_job(ip, script, args=()):
    with lock:
        if jobs.get(ip, {}).get("running"):
            return
        jobs[ip] = {"name": script, "lines": [], "running": True}

    def work():
        p = subprocess.Popen([os.path.join(HERE, script), ip, *args], cwd=HERE, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True, env=dict(os.environ, TV_IP=ip))
        for line in p.stdout:
            with lock:
                jobs[ip]["lines"].append(line.rstrip())
        p.wait()
        with lock:
            jobs[ip]["lines"].append("— hotovo —" if p.returncode == 0 else f"— skončilo s chybou ({p.returncode}) —")
            jobs[ip]["running"] = False

    threading.Thread(target=work, daemon=True).start()


PAGE = """<!doctype html><html lang="sk"><head><meta charset="utf-8"><title>Merk Kiosk inštalátor</title>
<style>
 :root{color-scheme:light dark;--bg:#f6f6f4;--fg:#1d1d1f;--muted:#6e6e73;--card:#fff;--line:#e3e3e0;--acc:#e8711c}
 @media (prefers-color-scheme:dark){:root{--bg:#141416;--fg:#f2f2f2;--muted:#9a9aa0;--card:#1e1e21;--line:#2e2e33}}
 body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.45 -apple-system,system-ui,sans-serif}
 main{max-width:760px;margin:0 auto;padding:32px 16px}
 h1{font-size:22px;margin:0 0 4px} p.sub{color:var(--muted);margin:0 0 20px}
 .card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin:12px 0}
 .row{display:flex;gap:12px;align-items:center;flex-wrap:wrap} .grow{flex:1;min-width:200px}
 .muted{color:var(--muted);font-size:13px}
 button{font:inherit;border:1px solid var(--line);background:var(--card);color:var(--fg);border-radius:8px;padding:8px 14px;cursor:pointer}
 button.primary{background:var(--acc);border-color:var(--acc);color:#fff} button:disabled{opacity:.5;cursor:default}
 pre{background:#0d0d0f;color:#d7d7d7;border-radius:8px;padding:10px;max-height:280px;overflow:auto;font-size:12px;white-space:pre-wrap;margin:10px 0 0}
 ol{padding-left:20px;margin:6px 0} .ok{color:#2e9e4a} .warn{color:#c47a00}
</style></head><body><main>
<h1>Merk Kiosk inštalátor</h1>
<p class="sub">Nájde televízory na tejto Wi-Fi a jedným tlačidlom z nich spraví menu kiosk.</p>
<div class="card"><b>Na TV raz ovládačom:</b>
 <ol><li>Nastavenia → Zariadenie → Informácie → 7× stlačiť <i>Zostava (Build)</i></li>
 <li>Nastavenia → Zariadenie → Pre vývojárov → <i>Ladenie USB</i> zapnúť</li>
 <li>Pri prvom pripojení TV opýta „Povoliť ladenie USB?“ → zaškrtnúť <i>Vždy povoliť</i> → OK</li></ol></div>
<div class="row"><button class="primary" id="scan" onclick="scan()">Hľadať televízory</button>
 <button id="many" onclick="installSelected()" disabled>Nainštalovať vybrané</button>
 <span class="muted" id="status"></span></div>
<div id="list"></div>
</main><script>
const $=s=>document.querySelector(s);
async function scan(){ $('#scan').disabled=true; $('#status').textContent='Hľadám (asi 10 s)…';
  const tvs=await (await fetch('/api/find')).json(); $('#scan').disabled=false;
  $('#status').textContent=tvs.length?`Nájdené: ${tvs.length}`:'Nič som nenašiel. Je TV zapnutá a na tej istej Wi-Fi?';
  $('#list').innerHTML=tvs.map(t=>`<div class="card" id="tv-${t.ip.replaceAll('.','-')}">
   <div class="row"><input type="checkbox" data-ip="${t.ip}" ${t.adb?'':'disabled'} onchange="sel()">
    <div class="grow"><b>${t.name}</b> <span class="muted">${t.ip}</span><br>
    <span class="muted" data-info>${t.adb?'Ladenie zapnuté. Zisťujem model…':'<span class=warn>Ladenie USB nie je zapnuté (pozri kroky hore)</span>'}</span></div>
   <button class="primary" ${t.adb?'':'disabled'} onclick="go('${t.ip}','install')">Nainštalovať kiosk</button>
   <button ${t.adb?'':'disabled'} onclick="go('${t.ip}','undo')">Vrátiť späť</button></div><pre hidden></pre></div>`).join('');
  for(const t of tvs) if(t.adb) info(t.ip);
  sel();
}
function selected(){ return [...document.querySelectorAll('input[data-ip]:checked')].map(c=>c.dataset.ip); }
function sel(){ const n=selected().length; $('#many').disabled=!n; $('#many').textContent=n?`Nainštalovať vybrané (${n})`:'Nainštalovať vybrané'; }
async function installSelected(){ const ips=selected(); if(!ips.length) return;
  if(!confirm(`Nainštalovať kiosk na ${ips.length} TV (${ips.join(', ')})? Pôjdu po jednej, každá sa na konci reštartuje.`)) return;
  $('#many').disabled=true; $('#scan').disabled=true;
  for(const [i,ip] of ips.entries()){ $('#status').textContent=`Inštalujem ${i+1}/${ips.length}: ${ip}`;
    await fetch(`/api/install?ip=${ip}`,{method:'POST'}); await waitDone(ip); }
  $('#status').textContent='Hotovo, všetky vybrané TV sú spracované.'; $('#scan').disabled=false; sel();
}
async function waitDone(ip){ while(true){ const j=await (await fetch('/api/log?ip='+ip)).json(); showLog(ip,j);
  if(!j.running) return; await new Promise(r=>setTimeout(r,1500)); } }
function showLog(ip,j){ const box=document.querySelector(`#tv-${ip.replaceAll('.','-')} pre`); box.hidden=false;
  box.textContent=j.lines.join('\\n'); box.scrollTop=1e9; }
async function info(ip){ const el=document.querySelector(`#tv-${ip.replaceAll('.','-')} [data-info]`);
  el.textContent='Pripájam sa… ak sa na TV objaví „Povoliť ladenie USB?“, daj Vždy povoliť a OK.';
  const r=await (await fetch('/api/info?ip='+ip)).json();
  el.innerHTML=r?`${r.model}, Android ${r.android} · kiosk ${r.kiosk?'<span class=ok>je nainštalovaný</span>':'nie je nainštalovaný'}`:'<span class=warn>TV nepovolila pripojenie. Povoľ na TV a hľadaj znova.</span>';
}
async function go(ip,what){ if(what==='install'&&!confirm('Nainštalovať kiosk na '+ip+'? TV sa na konci reštartuje.'))return;
  if(what==='undo'&&!confirm('Vrátiť '+ip+' do pôvodného stavu?'))return;
  await fetch(`/api/${what}?ip=${ip}`,{method:'POST'}); poll(ip);
}
async function poll(ip){ const box=document.querySelector(`#tv-${ip.replaceAll('.','-')} pre`); box.hidden=false;
  const j=await (await fetch('/api/log?ip='+ip)).json(); box.textContent=j.lines.join('\\n'); box.scrollTop=1e9;
  if(j.running) setTimeout(()=>poll(ip),1500);
}
scan();
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, data, code=200):
        body = json.dumps(data).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _ip(self):
        ip = parse_qs(urlparse(self.path).query).get("ip", [""])[0]
        return ip if re.fullmatch(r"\d{1,3}(\.\d{1,3}){3}", ip) else None

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/api/find":
            self._json(find_tvs())
        elif path == "/api/info" and self._ip():
            self._json(tv_model(self._ip()))
        elif path == "/api/log" and self._ip():
            with lock:
                j = jobs.get(self._ip(), {"lines": [], "running": False})
                self._json({"lines": list(j["lines"]), "running": j["running"]})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        path, ip = urlparse(self.path).path, self._ip()
        if not ip:
            return self._json({"error": "bad ip"}, 400)
        if path == "/api/install":
            start_job(ip, "provision.sh")
        elif path == "/api/undo":
            start_job(ip, "undo.sh", ("--apps", "--remove-app"))
        else:
            return self._json({"error": "not found"}, 404)
        self._json({"ok": True})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)   # only this Mac can open it
    url = f"http://127.0.0.1:{PORT}/"
    print(f"Merk Kiosk inštalátor beží na {url} (zatvorenie: Ctrl+C)")
    threading.Timer(0.8, lambda: webbrowser.open(url)).start()
    server.serve_forever()

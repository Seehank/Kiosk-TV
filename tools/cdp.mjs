// Prints network requests and console output of the kiosk's WebView for N seconds.
const secs = Number(process.argv[2] || 25);
const list = await (await fetch('http://localhost:9222/json')).json();
const t = list.find(x => x.type === 'page') || list[0];
console.log('page:', t.url);
const ws = new WebSocket(t.webSocketDebuggerUrl);
let id = 0; const counts = {}; let total = 0;
const send = (method, params = {}) => ws.send(JSON.stringify({ id: ++id, method, params }));
ws.onopen = () => { send('Network.enable'); send('Runtime.enable'); send('Log.enable'); send('Page.enable'); };
ws.onmessage = e => {
  const m = JSON.parse(e.data);
  if (m.method === 'Network.requestWillBeSent') {
    const u = m.params.request.url.split('?')[0]; counts[u] = (counts[u] || 0) + 1; total++;
  } else if (m.method === 'Runtime.consoleAPICalled') {
    console.log('console.' + m.params.type, m.params.args.map(a => a.value ?? a.description).join(' ').slice(0, 200));
  } else if (m.method === 'Runtime.exceptionThrown') {
    console.log('EXCEPTION', JSON.stringify(m.params.exceptionDetails).slice(0, 300));
  } else if (m.method === 'Page.frameNavigated' && !m.params.frame.parentId) {
    console.log('navigated:', m.params.frame.url);
  }
};
await new Promise(r => setTimeout(r, secs * 1000));
console.log('requests in', secs, 's:', total);
Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 12).forEach(([u, n]) => console.log(n, u));
process.exit(0);

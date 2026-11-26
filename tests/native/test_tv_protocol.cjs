const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const { createServer } = require("node:net");
const path = require("node:path");
const { createInterface } = require("node:readline");
const vm = require("node:vm");
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

async function until(predicate) {
  for (let i = 0; i < 120 && !predicate(); i++) await wait(15);
  assert(predicate());
}

async function run(cp, tvScripts) {
  const probe = createServer();
  await new Promise(resolve => probe.listen(0, "127.0.0.1", resolve));
  const port = probe.address().port;
  await new Promise(resolve => probe.close(resolve));
  const core = spawn("java", ["-cp", cp, "HarnessKt"]);
  const output = createInterface({ input: core.stdout })[Symbol.asyncIterator]();
  const events = new Map();
  const elements = new Map();
  const element = (id) => {
    const classes = new Set();
    if (!elements.has(id)) elements.set(id, {
      classList: { add: (name) => classes.add(name), remove: (name) => classes.delete(name), contains: (name) => classes.has(name) },
      textContent: "", value: "", style: {}, ownerDocument: document, offsetHeight: 25,
      clientWidth: 1920, clientHeight: 1080, children: [],
      addEventListener(kind, fn) { events.set(id + ":" + kind, fn); },
      appendChild(child) { this.children.push(child); },
      removeChild(child) { this.children = this.children.filter(node => node !== child); },
      focus() {},
    });
    return elements.get(id);
  };
  const document = {
    hidden: false, getElementById: element, addEventListener(kind, fn) { events.set(kind, fn); },
    createElement() { return { textContent: "", style: {}, offsetHeight: 25 }; },
  };
  const window = {
    setTimeout, clearTimeout,
    localStorage: { getItem() {}, setItem() {} },
    addEventListener(kind, fn) { events.set(kind, fn); },
  };
  const context = vm.createContext({ window, document, WebSocket });
  for (const file of ["text-overlay.js", "app.js"]) {
    vm.runInContext(fs.readFileSync(path.join(tvScripts, file), "utf8"), context);
  }
  async function command(cmd) {
    core.stdin.write(cmd + "\n");
    return (await output.next()).value;
  }
  try {
    assert.equal((await output.next()).value, "READY");
    assert.equal(await command("START " + port + " -"), "START OK");
    events.get("load")();
    element("tabletIp").value = "127.0.0.1";
    element("tabletPort").value = String(port);
    events.get("connect:click")();
    await until(() => element("connectionStatus").textContent === "Cihaza bağlandı.");
    await wait(100);
    const shown = element("subtitle");
    const layer = element("textOverlay");
    assert.equal(await command("TTL 850 TV altyazısı"), "SEND SENT 1");
    await until(() => shown.textContent === "TV altyazısı");
    await wait(500);
    assert.equal(await command("REFRESH 1000 TV altyazısı"), "true");
    await wait(420);
    assert.equal(shown.textContent, "TV altyazısı");
    assert.equal(await command("OVERLAY"), "1");
    await until(() => layer.children.length === 2);
    assert.equal(shown.textContent, "");
    assert.equal(layer.children[0].textContent, 'Görev "başladı"');
    await until(() => layer.children.length === 1);
    assert.equal(layer.children[0].textContent, "İkinci satır");
    assert.equal(await command("CLEAROVERLAY"), "1");
    await until(() => layer.children.length === 0);
    assert.equal(await command("TTL 900 Altyazı modu"), "SEND SENT 1");
    await until(() => shown.textContent === "Altyazı modu");
    assert.equal(await command("OVERLAY"), "1");
    await until(() => layer.children.length === 2 && shown.textContent === "");
    assert.equal(await command("STOP"), "STOPPED");
    await until(() => shown.textContent === "" && layer.children.length === 0);
    assert.equal(await command("START " + port + " -"), "START OK");
    await until(() => element("connectionStatus").textContent === "Cihaza bağlandı.");
    assert.equal(await command("TTL 900 Bağlantı yeniden kuruldu"), "SEND SENT 1");
    await until(() => shown.textContent === "Bağlantı yeniden kuruldu");
    document.hidden = true;
    events.get("visibilitychange")();
  } finally {
    core.stdin.end();
    core.kill();
  }
}
const [cp, tvScripts] = process.argv.slice(2);
if (!cp || !tvScripts) throw new Error("Usage: node test_tv_protocol.cjs <harness-classpath> <Tizen-js-directory>");
run(cp, tvScripts).then(() => {
  console.log("Kotlin WebSocket server and Tizen client over TCP (including reconnect): PASS");
}).catch(error => { console.error(error); process.exitCode = 1; });

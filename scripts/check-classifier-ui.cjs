/* Dependency-free interaction smoke checks for the mock Web application. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../system-direction-classifier-web/src/main/resources/public/classifier');

function boot(hash = '', storage = new Map()) {
  const listeners = {}, windowListeners = {}, nodes = {};
  const node = id => nodes[id] ||= {innerHTML: '', append() {}, remove() {}};
  const location = {hash};
  const window = {location, scrollTo() {}, addEventListener(type, fn) {windowListeners[type] = fn;}};
  window.history = {pushState(_, __, url) {location.hash = url;}, replaceState(_, __, url) {location.hash = url;}};
  const document = {
    querySelector: node,
    createElement() {return {click() {}, remove() {}};},
    body: {append() {}},
    addEventListener(type, fn) {(listeners[type] ||= []).push(fn);}
  };
  const context = vm.createContext({window, document, URLSearchParams, console,
    localStorage: {getItem: key => storage.get(key) || null, setItem: (key, value) => storage.set(key, value)},
    setTimeout() {}, Blob, URL});
  for (const name of ['mock-data.js', 'mock-store.js', 'router.js', 'app.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets, name), 'utf8'), context, {filename: name});
  }
  const event = (type, target) => listeners[type].forEach(fn => fn({target, preventDefault() {}}));
  return {
    storage, window,
    html: () => nodes['#app'].innerHTML,
    overlay: () => nodes['#overlay'].innerHTML,
    click(action) {event('click', {closest: () => ({dataset: {action}})});},
    change(bind, value, type = 'text') {event('change', {dataset: {bind}, value, type});},
    route(hash) {location.hash = hash; windowListeners.popstate();},
    saved: () => JSON.parse(storage.get('direction-classifier.mock.v1'))
  };
}

const app = boot();
assert.match(app.html(), /工作台总览/);
for (const route of ['methods', 'knowledge', 'debug', 'batch', 'settings']) {
  app.click('nav:' + route);
  assert.match(app.html(), /class="page-title"/);
}
app.click('methodOpen:m1');
app.change('method:router:maxClusters', 6, 'number');
app.click('nav:knowledge');
assert.match(app.overlay(), /草稿/);
app.click('leaveSave');
const oldVersion = JSON.stringify(app.saved().methods[0].versions[0]);
assert.equal(app.saved().methods[0].draft.config.router.maxClusters, 6);
app.click('methodOpen:m1');
app.click('methodSaveVersion');
app.change('modal:summary', '测试保存新版本');
app.click('methodCommitVersion');
assert.equal(app.saved().methods[0].versions[0].number, 'v2.4');
assert.equal(JSON.stringify(app.saved().methods[0].versions[1]), oldVersion);
const bookmarked = app.window.location.hash;
assert.match(boot(bookmarked, app.storage).html(), /已保存 v2.4/);

app.click('methodDebugVersion:v2.3');
app.click('dbgSample');
app.click('dbgRun');
assert.match(app.html(), /0.63/);
app.click('dbgSaveHistory');
assert.equal(app.saved().histories[0].version, 'v2.3');
app.click('dbgAddCandidate');
app.click('dbgApplyCandidate');
assert.match(app.html(), /待重算/);
assert.equal(app.saved().histories[0].adjustments.length, 0);
app.click('dbgRestore');

app.click('kbOpen:kb1');
app.click('kbCreateDraft');
app.change('kb:profile:1201:definition', '更新定义');
app.click('kbSaveDraft');
assert.equal(app.saved().kbs[0].draft.resources.summaries[0].stale, true);
assert.notEqual(app.saved().kbs[0].versions[0].resources.profiles[0].definition, '更新定义');

app.click('nav:batch');
app.click('batchNew');
app.change('batch:name', '批量演示任务');
app.click('batchNext');
app.click('batchSample');
assert.match(app.html(), /编号重复/);
app.change('batch:skipInvalid', true, 'checkbox');
app.click('batchNext');
app.click('batchSubmit');
assert.equal(app.saved().tasks[0].total, 3);
app.click('taskSimulate:' + app.saved().tasks[0].id);
assert.equal(app.saved().tasks[0].success, 3);

app.click('nav:settings');
app.click('connAdd');
app.change('modal:name', '本地测试连接');
app.change('modal:url', 'http://localhost:9999/v1');
app.change('modal:model', 'mock-model');
app.click('connSave');
assert.equal(app.saved().connections.at(-1).model, 'mock-model');
app.route('#methodDetail?methodId=missing');
assert.match(app.html(), /管理识别流程/);
app.route('#unknown');
assert.match(app.html(), /工作台总览/);

const restored = boot('#debug?dbgMethodId=m1&dbgVersion=v2.3', app.storage);
assert.match(restored.html(), /value="v2.3" selected/);
boot('', new Map([['direction-classifier.mock.v1', '{invalid']]));
console.log('PASS: navigation, bookmarks, versions, draft guard, debug snapshots, knowledge staleness, batch validation, model connections and storage fallback');

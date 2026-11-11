/* Dependency-free checks for workspace routing and demo execution pages. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../system-direction-classifier-web/src/main/resources/public/classifier');
const tick = () => new Promise(setImmediate);
const version = {id:7,versionNo:1,state:'saved',knowledgeName:'学科知识库',knowledgeVersionNo:1};
const method = {id:5,name:'业务库识别方法',ownerLogin:'admin',taxonomyName:'教育部',defaultNumber:1,latestNumber:1,
  defaultVersionId:7,latestVersionId:7,versions:[version]};

function boot(hash = '', storage = new Map(), offline = false) {
  const listeners = {}, windowListeners = {}, nodes = {};
  const node = id => nodes[id] ||= {innerHTML:'',append() {},remove() {}};
  const location = {hash};
  const window = {location,scrollTo() {},addEventListener(type,fn) {windowListeners[type]=fn;}};
  window.ClassifierKnowledge = {search() {},mount() {}};
  window.ClassifierModelConnections = {mount() {},unmount() {},dismiss() {return false;}};
  window.history = {pushState(_,__,url) {location.hash=url;},replaceState(_,__,url) {location.hash=url;}};
  const document = {querySelector:node,createElement() {return {click() {},remove() {}};},
    body:{append() {}},addEventListener(type,fn) {(listeners[type] ||= []).push(fn);}};
  const fetch = async url => {
    const get = (data, ok = true) => ({ok,status:ok?200:404,json:async()=>({success:ok,data,message:ok?'':'方法不可见'})});
    if (offline) return get(null,false);
    if (url === '/api/methods/options') return get([method]);
    if (url.startsWith('/api/methods?')) return get({items:[method],taxonomies:[],canCreate:true,total:1,page:1,pageSize:20});
    if (url === '/api/methods/5') return get(method);
    if (url === '/api/methods/5/versions/7') return get({...version,stages:[]});
    return get(null,false);
  };
  const context = vm.createContext({window,document,URLSearchParams,console,fetch,Blob,URL,setTimeout() {},
    localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)}});
  for (const name of ['mock-data.js','mock-store.js','router.js','methods.js','app.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context,{filename:name});
  }
  const event = (type,target) => listeners[type].forEach(fn=>fn({target,preventDefault() {}}));
  return {
    html:()=>nodes['#app'].innerHTML,
    methodHtml:()=>nodes['#method-page'].innerHTML,
    click(action) {event('click',{closest:()=>({dataset:{action}})});},
    methodClick(action) {nodes['#method-page'].onclick({target:{closest:()=>({dataset:{methodAction:action}})}});},
    change(bind,value,type='text') {event('change',{dataset:{bind},value,type});},
    route(value) {location.hash=value;windowListeners.popstate();},
    saved:()=>JSON.parse(storage.get('direction-classifier.mock.v1'))
  };
}

async function run() {
  const storage = new Map([['direction-classifier.mock.v1',JSON.stringify({methods:[{name:'过期演示方法'}],tasks:[],histories:[]})]]);
  const app = boot('',storage);
  await tick();
  assert.match(app.html(),/工作台总览/);
  assert.match(app.html(),/业务库识别方法/);
  assert.equal(app.saved().methods,undefined);
  app.click('nav:methods');
  await tick();
  assert.match(app.methodHtml(),/业务库识别方法/);
  assert.doesNotMatch(app.methodHtml(),/过期演示方法/);
  app.methodClick('open:5');
  await tick();
  assert.match(app.methodHtml(),/已保存 v1/);
  app.route('#methodDetail?methodId=404');
  await tick();
  assert.match(app.methodHtml(),/方法不可见/);
  app.click('nav:debug');
  assert.match(app.html(),/业务库识别方法/);
  app.click('dbgSample');
  app.click('dbgRun');
  app.click('dbgSaveHistory');
  assert.equal(app.saved().histories[0].version,'v1');
  app.click('dbgAddCandidate');
  app.click('dbgApplyCandidate');
  assert.match(app.html(),/待重算/);
  assert.equal(app.saved().histories[0].adjustments.length,0);

  app.click('nav:batch');
  app.click('batchNew');
  assert.match(app.html(),/业务库识别方法/);
  app.change('batch:name','批量演示任务');
  app.click('batchNext');
  app.click('batchSample');
  assert.match(app.html(),/编号重复/);
  app.change('batch:skipInvalid',true,'checkbox');
  app.click('batchNext');
  app.click('batchSubmit');
  assert.equal(app.saved().tasks[0].total,3);
  app.click('taskSimulate:'+app.saved().tasks[0].id);
  assert.equal(app.saved().tasks[0].success,3);
  assert.equal(app.saved().methods,undefined);

  app.click('nav:knowledge');
  assert.match(app.html(),/id="knowledge-page"/);
  app.click('nav:settings');
  assert.match(app.html(),/id="model-connection-page"/);
  const disconnected=boot('#methods',new Map(),true);
  await tick();
  assert.match(disconnected.methodHtml(),/读取方法失败/);
  assert.doesNotMatch(disconnected.methodHtml(),/多阶段学科交叉识别/);
  console.log('PASS: database method references, legacy mock cleanup, routes, and existing execution demonstrations');
}

run().catch(error=>{console.error(error);process.exitCode=1;});

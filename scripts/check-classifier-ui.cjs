/* Dependency-free checks for workspace routing and the debug API contract. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assets = path.join(__dirname, '../system-direction-classifier-web/src/main/resources/public/classifier');
const tick = () => new Promise(setImmediate);
const version = {id:7,versionNo:1,state:'saved',knowledgeName:'学科知识库',knowledgeVersionNo:1};
const method = {id:5,name:'业务库识别方法',ownerLogin:'admin',taxonomyName:'教育部',defaultNumber:1,latestNumber:1,
  defaultVersionId:7,latestVersionId:7,versions:[version]};
const input = {type:'学术论文',title:'验收用论文'};
const runSnapshot = {id:9,methodId:5,methodVersionNo:1,methodName:method.name,knowledgeVersionNo:1,
  knowledgeName:version.knowledgeName, input, status:'succeeded', startStageCode:'parse',
  stages:['parse','router','recall','judge','post','result'].map(stageCode=>({stageCode,status:'succeeded'})),
  result:{disciplines:[{code:'0812',name:'服务端返回学科',finalRank:1,finalLevel:'高度相关',reason:'服务端返回理由'}],metrics:[]}};

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
  const requests = [];
  const fetch = async (url, options = {}) => {
    requests.push({url,options});
    const get = (data, ok = true) => ({ok,status:ok?200:404,json:async()=>({success:ok,data,message:ok?'':'方法不可见'})});
    if (offline) return get(null,false);
    if (url === '/api/methods/options') return get([method]);
    if (url.startsWith('/api/methods?')) return get({items:[method],taxonomies:[],canCreate:true,total:1,page:1,pageSize:20});
    if (url === '/api/methods/5') return get(method);
    if (url === '/api/methods/5/versions/7') return get({...version,stages:[]});
    if (url === '/api/auth/csrf') return get({csrfToken:'csrf-test'});
    if (url === '/api/debug/runs' && options.method === 'POST') return get(runSnapshot);
    if (url === '/api/debug/runs/9') return get(runSnapshot);
    if (url === '/api/debug/runs/9/save') return get({id:12,runId:9});
    if (url === '/api/debug/history/12') return get({...runSnapshot,historyId:12});
    if (url.startsWith('/api/debug/runs/9/stages/')) return get({status:'succeeded',output:{parsed_fields:input},
      calls:[{modelId:'api-model',logicalCallNo:1,attemptNo:1,status:'succeeded',request:{messages:[{content:'调用提示词'}]}}]});
    return get(null,false);
  };
  const context = vm.createContext({window,document,URLSearchParams,console,fetch,Blob,URL,setTimeout() {},clearTimeout() {},
    localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)}});
  for (const name of ['mock-data.js','mock-store.js','router.js','methods.js','debug.js','app.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context,{filename:name});
  }
  const event = (type,target) => listeners[type].forEach(fn=>fn({target,preventDefault() {}}));
  return {
    html:()=>nodes['#app'].innerHTML,
    requests,
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
  app.change('debug:title',input.title);
  app.click('debug:run');
  await tick();
  const submitted = app.requests.find(item=>item.url==='/api/debug/runs');
  assert.equal(submitted.options.headers['X-CSRF-Token'],'csrf-test');
  assert.equal(JSON.parse(submitted.options.body).methodVersionId,7);
  assert.equal(JSON.parse(submitted.options.body).input.title,input.title);
  assert.match(app.html(),/服务端返回学科/);
  assert.match(app.html(),/未提供/);
  assert.doesNotMatch(app.html(),/演示结果|修改要素句子/);
  app.click('debug:step:judge');
  app.click('debug:calls');
  await tick();
  assert.match(app.html(),/调用提示词/);
  app.click('debug:save');
  await tick();
  assert.ok(app.requests.some(item=>item.url==='/api/debug/runs/9/save'));
  assert.equal(app.saved().histories,undefined);
  app.route('#debug?dbgHistoryId=12');
  await tick();
  assert.match(app.html(),/正在查看历史快照/);

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
  console.log('PASS: workspace routes, debug API submission, call evidence, history snapshot and mock cleanup');
}

run().catch(error=>{console.error(error);process.exitCode=1;});

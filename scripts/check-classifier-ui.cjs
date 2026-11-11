/* Dependency-free checks for workspace routing and the execution API contracts. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
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
const batchItem = {id:31,sourceRowNo:2,title:input.title,externalRecordId:'0001',status:'succeeded',
  currentRunId:9,adoptedResultId:1,mainDiscipline:'服务端返回学科',disciplines:runSnapshot.result.disciplines};
const batchTask = {id:14,name:'业务库批量任务',methodId:5,methodVersionNo:1,methodName:method.name,
  knowledgeVersionNo:1,knowledgeName:version.knowledgeName,status:'succeeded',fileName:'test.csv',importSummary:{total:1,valid:1,invalid:0},
  progress:{total:1,pending:0,running:0,succeeded:1,failed:0},disciplineOptions:[{code:'0812',name:'服务端返回学科'}],
  results:{items:[batchItem],total:1,page:1,pageSize:20}};

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
    if (url === '/api/batch/tasks' && options.method === 'POST') return get({...batchTask,status:'draft'});
    if (url === '/api/batch/tasks' || url.startsWith('/api/batch/tasks?')) return get({items:[batchTask],page:1,total:1,pageSize:20});
    if (url === '/api/batch/tasks/14/imports/inspect') return get({sheets:[],columns:['id','title'],total:1,
      rows:[{sourceRowNo:2,cells:['0001',input.title]}]});
    if (url === '/api/batch/tasks/14/imports') return get({importId:88,summary:{total:1,valid:1,invalid:0},
      rows:[{sourceRowNo:2,input:{...input,externalId:'0001'},issues:[]}]});
    if (url === '/api/batch/tasks/14/submit') return get({...batchTask,status:'queued'});
    if (url.startsWith('/api/batch/tasks/14?')) return get(batchTask);
    if (url === '/api/batch/tasks/14/items/31') return get({item:batchItem,run:runSnapshot,
      attempts:[{id:9,batchAttemptNo:1,status:'succeeded'}]});
    if (url.startsWith('/api/batch/tasks/14/items/31/runs/9/stages/')) return get({status:'succeeded',
      output:{judgments:[]},calls:[{modelId:'api-model',logicalCallNo:1,attemptNo:1,status:'succeeded',request:{messages:[{content:'批量调用提示词'}]}}]});
    return get(null,false);
  };
  class FileReader { readAsDataURL() { this.result='data:text/csv;base64,aWQsdGl0bGU='; this.onload(); } }
  const context = vm.createContext({window,document,URLSearchParams,console,fetch,Blob,URL,crypto:webcrypto,FileReader,setTimeout() {},clearTimeout() {},
    localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)}});
  for (const name of ['router.js','methods.js','debug.js','batch.js','app.js']) {
    vm.runInContext(fs.readFileSync(path.join(assets,name),'utf8'),context,{filename:name});
  }
  const event = (type,target) => listeners[type].forEach(fn=>fn({target,preventDefault() {}}));
  return {
    html:()=>nodes['#app'].innerHTML,
    overlay:()=>nodes['#overlay'].innerHTML,
    requests,
    methodHtml:()=>nodes['#method-page'].innerHTML,
    click(action) {event('click',{closest:()=>({dataset:{action}})});},
    methodClick(action) {nodes['#method-page'].onclick({target:{closest:()=>({dataset:{methodAction:action}})}});},
    change(bind,value,type='text') {event('change',{dataset:{bind},value,type});},
    file(name) {event('change',{dataset:{},hasAttribute:key=>key==='data-batch-file',files:[{name,size:32}]});},
    route(value) {location.hash=value;windowListeners.popstate();},
    saved:()=>JSON.parse(storage.get('direction-classifier.mock.v1'))
  };
}

async function run() {
  const storage = new Map([['direction-classifier.mock.v1',JSON.stringify({
    methods:[{name:'过期演示方法'}],tasks:[{name:'过期演示任务'}],histories:[{title:'旧本地调试'}]})]]);
  const app = boot('',storage);
  await tick();
  assert.match(app.html(),/工作台总览/);
  assert.match(app.html(),/业务库识别方法/);
  assert.doesNotMatch(app.html(),/过期演示任务/);
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
  assert.equal(app.saved().histories[0].title,'旧本地调试');
  app.route('#debug?dbgHistoryId=12');
  await tick();
  assert.match(app.html(),/正在查看历史快照/);

  app.click('nav:batch');
  await tick();
  assert.match(app.html(),/业务库批量任务/);
  assert.doesNotMatch(app.html(),/过期演示任务/);
  app.click('batch:new');
  assert.match(app.html(),/业务库识别方法/);
  app.change('batch:name','批量验收任务');
  app.click('batch:next');
  await tick();
  app.file('test.csv');
  await tick();
  assert.match(app.html(),/列映射/);
  app.click('batch:check');
  await tick();
  assert.match(app.html(),/有效 1/);
  app.click('batch:next');
  await tick();
  app.click('batch:submit');
  await tick();
  const batchSubmit=app.requests.find(item=>item.url==='/api/batch/tasks/14/submit');
  assert.equal(JSON.parse(batchSubmit.options.body).importId,88);
  assert.match(app.html(),/服务端返回学科/);
  app.route('#taskDetail?batchTaskId=14');
  await tick();
  app.click('batch:item:31');
  await tick();
  app.click('batch:phase:judge');
  await tick();
  app.click('batch:callDetails');
  await tick();
  assert.match(app.overlay(),/批量调用提示词/);
  app.click('batch:copyInput');
  assert.match(app.html(),/验收用论文/);
  assert.equal(app.saved().tasks[0].name,'过期演示任务');

  app.click('nav:knowledge');
  assert.match(app.html(),/id="knowledge-page"/);
  app.click('nav:settings');
  assert.match(app.html(),/id="model-connection-page"/);
  const disconnected=boot('#methods',new Map(),true);
  await tick();
  assert.match(disconnected.methodHtml(),/读取方法失败/);
  assert.doesNotMatch(disconnected.methodHtml(),/多阶段学科交叉识别/);
  console.log('PASS: workspace routes, single debug, batch upload and submit, restored results, and shared call evidence');
}

run().catch(error=>{console.error(error);process.exitCode=1;});

/* 学科交叉识别平台 Web 入口。 */
(() => {
'use strict';
const $ = sel => document.querySelector(sel);
const clone = x => JSON.parse(JSON.stringify(x));
const esc = x => String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const A = (x,a='') => `<span ${a}>${esc(x)}</span>`;
const ic = (n,size=17) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${({home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1z"/>',file:'<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h4"/>',layers:'<path d="m12 3 9 5-9 5-9-5zM3 12l9 5 9-5M3 16l9 5 9-5"/>',settings:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.6 2.6-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.6V21h-4v-.2a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1-2.6-2.6.1-.1A1.7 1.7 0 0 0 5 15a1.7 1.7 0 0 0-1.6-1H3v-4h.4A1.7 1.7 0 0 0 5 9a1.7 1.7 0 0 0-.3-1.9l-.1-.1 2.6-2.6.1.1A1.7 1.7 0 0 0 9.2 4a1.7 1.7 0 0 0 1-1.6V2h4v.4a1.7 1.7 0 0 0 1 1.6 1.7 1.7 0 0 0 1.9-.3l.1-.1 2.6 2.6-.1.1A1.7 1.7 0 0 0 19.4 9a1.7 1.7 0 0 0 1.6 1h.1v4H21a1.7 1.7 0 0 0-1.6 1z"/>',book:'<path d="M12 7v14M3 18V5a2 2 0 0 1 2-2h3a4 4 0 0 1 4 4 4 4 0 0 1 4-4h3a2 2 0 0 1 2 2v13h-5a4 4 0 0 0-4 3 4 4 0 0 0-4-3z"/>',workflow:'<circle cx="5" cy="5" r="2"/><circle cx="19" cy="5" r="2"/><circle cx="12" cy="19" r="2"/><path d="M6.5 6.5 11 17M17.5 6.5 13 17"/>',search:'<circle cx="10.8" cy="10.8" r="7"/><path d="m16 16 5 5"/>',plus:'<path d="M12 5v14M5 12h14"/>',arrow:'<path d="M5 12h14m-6-6 6 6-6 6"/>',chevron:'<path d="m9 18 6-6-6-6"/>',down:'<path d="m6 9 6 6 6-6"/>',up:'<path d="m6 15 6-6 6 6"/>',check:'<path d="m5 12 5 5L20 7"/>',checkcircle:'<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>',alert:'<circle cx="12" cy="12" r="9"/><path d="M12 7v6m0 4h.01"/>',history:'<path d="M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5"/><path d="M12 7v5l3 2"/>',edit:'<path d="m15 5 4 4M4 20l5-.9L20 7a2.1 2.1 0 0 0-3-3L4.9 16z"/>',copy:'<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',upload:'<path d="M12 16V3m-5 5 5-5 5 5M4 16v4h16v-4"/>',download:'<path d="M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4"/>',filter:'<path d="M3 6h18M6 12h12M10 18h4"/>',refresh:'<path d="M20 11a8 8 0 0 0-14-5L3 9m0-5v5h5M4 13a8 8 0 0 0 14 5l3-3m0 5v-5h-5"/>',play:'<path d="m8 5 11 7-11 7z"/>',more:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',close:'<path d="M5 5l14 14M19 5 5 19"/>',link:'<path d="M10 13a5 5 0 0 0 7 .4l3-3a5 5 0 0 0-7-7l-2 2M14 11a5 5 0 0 0-7-.4l-3 3a5 5 0 0 0 7 7l2-2"/>',bolt:'<path d="m13 2-9 12h7l-1 8 10-13h-7z"/>',spark:'<path d="m12 2 2 7 7 3-7 2-2 8-2-8-8-2 8-3z"/>',code:'<path d="m8 8-4 4 4 4m8-8 4 4-4 4m-3-11-2 14"/>',database:'<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 4 18 4 18 0V5M3 12c0 4 18 4 18 0"/>',eye:'<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l4 2"/>',menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',trash:'<path d="M4 7h16M9 7V4h6v3m-9 0 1 14h10l1-14"/>',doc:'<path d="M7 3h8l4 4v14H5V3zM15 3v5h4M8 12h8M8 16h8"/>',flask:'<path d="M9 3h6m-5 0v7l-6 9a2 2 0 0 0 2 3h12a2 2 0 0 0 2-3l-6-9V3M8 16h8"/>',grid:'<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v5m0-8h.01"/>',tag:'<path d="M3 3h9l9 9-9 9-9-9z"/><circle cx="8" cy="8" r="1"/>',server:'<rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6h.01M7 17h.01"/>',save:'<path d="M4 3h14l3 3v15H3V3zm3 0v7h10V3M7 21v-8h10v8"/>',question:'<circle cx="12" cy="12" r="9"/><path d="M9 9a3 3 0 1 1 5 2l-2 2v1m0 3h.01"/>'}[n]||'<circle cx="12" cy="12" r="9"/>')}</svg>`;
const btn=(label, action, opts='')=>`<button type="button" class="button ${opts}" data-action="${action}">${label}</button>`;
const badge=(t,type='neutral')=>`<span class="badge ${type}">${esc(t)}</span>`;
const field=(label,value,bind,opts={})=>`<div class="field"><label class="label">${esc(label)}</label>${opts.type==='select'?`<select class="form-control ${opts.small?'sm':''}" data-bind="${esc(bind)}" ${opts.disabled?'disabled':''}>${(opts.options||[]).map(o=>{const v=typeof o==='string'?o:o[0],l=typeof o==='string'?o:o[1];return `<option value="${esc(v)}" ${String(value)===String(v)?'selected':''}>${esc(l)}</option>`}).join('')}</select>`:opts.type==='textarea'?`<textarea class="form-control textarea" data-bind="${esc(bind)}" rows="${opts.rows||4}" ${opts.disabled?'disabled':''} placeholder="${esc(opts.placeholder||'')}">${esc(value)}</textarea>`:`<input class="form-control ${opts.small?'sm':''}" type="${opts.type||'text'}" data-bind="${esc(bind)}" value="${esc(value)}" ${opts.disabled?'disabled':''} placeholder="${esc(opts.placeholder||'')}" ${opts.min!==undefined?`min="${opts.min}"`:''} ${opts.max!==undefined?`max="${opts.max}"`:''}/>`}${opts.hint?`<div class="hint">${opts.hint}</div>`:''}</div>`;
const opt=(arr,v)=>arr.map(x=>{let k=Array.isArray(x)?x[0]:x, l=Array.isArray(x)?x[1]:x;return `<option value="${esc(k)}" ${String(v)===String(k)?'selected':''}>${esc(l)}</option>`}).join('');
const note=(s,type='')=>`<div class="inline-note ${type}">${ic(type==='red'?'alert':'info',16)}<span>${s}</span></div>`;
const knowledge = window.ClassifierKnowledge;
const models = window.ClassifierModelConnections;
const methods = window.ClassifierMethods;
let methodChoices = [];
let methodOptionsError = '';
let methodOptionsToken = 0;
const ui={page:'overview',methodId:'',methodTab:'flow',methodStage:'router',methodView:'',methodVersion:'',kbId:'',kbTab:'profiles',kbSelected:'',kbVersion:'',dbgMethodId:'',dbgVersion:'',dbgInput:{type:'学术论文',title:'',keywords:'',abstract:'',journal:'',doi:'',year:'',externalId:''},dbgMore:false,dbgRunId:'',dbgHistoryId:'',batchTaskId:'',modal:null,modalValues:{},pending:null};
const toast=(s,error=false)=>{const d=document.createElement('div');d.className='toast'+(error?' error':'');d.textContent=s;$('#toasts').append(d);setTimeout(()=>d.remove(),3300)};
const getMver=(m,num)=>m?.versions.find(v=>v.number===num)||m?.versions.find(v=>v.number===m.defaultVersion)||m?.versions[0];
const router = window.ClassifierRouter;
const goto=(page,params={},replace=false)=>{
 if(!router.has(page))page='overview';
 if(methods.guard(page,params,()=>goto(page,params,replace)))return false;
 if(['methods','methodDetail'].includes(ui.page)&&!['methods','methodDetail'].includes(page))methods.unmount();
 ui.page=page;Object.assign(ui,params);
 if(page==='debug'&&!ui.dbgRunId&&!ui.dbgHistoryId)selectDebugVersion();
 router.write(ui,replace);render();
 if(page==='debug')debug.route();
 batch.route();
 window.scrollTo({top:0,behavior:'instant'});return true;
};
const openModal=(type,values={})=>{ui.modal=type;ui.modalValues=clone(values);renderModal()};
const closeModal=()=>{ui.modal=null;ui.modalValues={};$('#overlay').innerHTML=''};
const crumbName=()=>({overview:'总览',methods:'识别方法',methodDetail:'方法详情',knowledge:'学科知识库',kbDetail:'知识库详情',debug:'单篇调试',batch:'批量识别',batchWizard:'新建批量任务',taskDetail:'任务详情',settings:'模型连接'})[ui.page]||'工作台';
const currentSidePage=()=>['methodDetail'].includes(ui.page)?'methods':['kbDetail'].includes(ui.page)?'knowledge':['batchWizard','taskDetail'].includes(ui.page)?'batch':ui.page;
const navbar=()=>{const nav=(page,label,icon)=>`<button type="button" class="side-link ${currentSidePage()===page?'active':''}" data-action="nav:${page}" title="${label}">${ic(icon)}<span class="nav-word">${label}</span></button>`;return `<aside class="sidebar"><div class="brand"><span class="brand-mark">${ic('workflow',31)}</span><span class="brand-word">学科交叉识别平台</span></div><div class="side-section-label">工作空间</div>${nav('overview','总览','home')}${nav('debug','单篇调试','flask')}${nav('batch','批量识别','layers')}<div class="side-section-label">资源与配置</div>${nav('methods','识别方法','workflow')}${nav('knowledge','学科知识库','book')}<div class="side-bottom">${nav('settings','模型连接','settings')}<a href="/identity/" class="side-link"><span class="nav-word">用户与团队</span></a><div class="side-footer">DESIGN PROTOTYPE · 2026</div></div></aside>`};
const topbar=()=>`<header class="topbar"><div class="crumbs"><span>工作空间</span><span>/</span>${['methodDetail','kbDetail','taskDetail','batchWizard'].includes(ui.page)?`<span>${ui.page==='methodDetail'?'识别方法':ui.page==='kbDetail'?'学科知识库':'批量识别'}</span><span>/</span>`:''}<strong>${esc(crumbName())}</strong></div><div class="top-actions"><button class="search-pill" data-action="globalSearch">${ic('search',15)}<span>搜索方法、任务或知识库</span><kbd>Ctrl K</kbd></button><button class="icon-button" title="原型使用说明" data-action="help">${ic('question',19)}</button><div class="avatar" title="演示账号">研</div></div></header>`;
const pageHead=(eyebrow,title,sub,actions='')=>`<div class="page-head"><div><div class="eyebrow">${eyebrow}</div><h1 class="page-title">${title}</h1><p class="page-sub">${sub}</p></div><div class="head-actions">${actions}</div></div>`;
const empty=(title,desc,action='')=>`<div class="empty"><div class="empty-icon">${ic('search',22)}</div><div class="empty-title">${title}</div><div class="empty-desc">${desc}</div>${action}</div>`;
const navButton=(title,page,icon)=>`<button class="tile" data-action="nav:${page}"><span class="tile-icon">${ic(icon,21)}</span><span class="grow"><div class="tile-title">${title}</div><div class="tile-desc">${({debug:'使用已保存的方法版本，逐阶段观察识别结果',batch:'上传数据并运行批量任务，查看进度与导出结果',methods:'配置识别流程、阶段规则与模型，管理版本'})[page]}</div></span><span class="tile-arrow">${ic('arrow',17)}</span></button>`;
function renderOverview(){const recent=methodChoices.slice(0,3);return `${pageHead('WORKSPACE / OVERVIEW','工作台总览','管理识别方法、验证单篇结果并执行批量任务。所有操作均围绕固定的方法与知识库版本进行。',`${btn(ic('plus',15)+' 新建方法','methodNew')}${btn(ic('play',15)+' 开始单篇调试','nav:debug','primary')}`)}<div class="tiles">${navButton('单篇调试','debug','flask')}${navButton('批量识别','batch','layers')}${navButton('识别方法','methods','workflow')}</div><div class="section-head section-space"><h2 class="section-title">继续工作</h2><span class="section-sub">快速回到最近操作的内容</span></div><div class="split-2"><section class="panel"><div class="panel-head"><span class="panel-title">最近使用的方法</span>${btn('查看全部 '+ic('arrow',13),'nav:methods','ghost small')}</div>${recent.map(m=>`<div class="row-item"><span class="tile-icon" style="width:35px;height:35px;border-radius:8px">${ic('workflow',18)}</span><div class="grow" style="min-width:0"><div class="row-name">${esc(m.name)}</div><div class="row-sub">${badge(m.defaultVersion,'green')}　知识库：${esc(getMver(m)?.knowledgeName)} ${esc(getMver(m)?.knowledgeVersion)}</div></div><button class="icon-button" data-action="methodOpen:${m.id}" title="打开方法">${ic('arrow',17)}</button></div>`).join('')}</section><section class="panel"><div class="panel-head"><span class="panel-title">最近任务</span>${btn('查看全部 '+ic('arrow',13),'nav:batch','ghost small')}</div>${batch.overview()}</section></div><section class="panel section-space"><div class="path-row"><span class="bold" style="font-size:13px">推荐使用路径</span>${[['1','配置方法','定义规则与模型'],['2','单篇验证','检查阶段结果'],['3','批量识别','使用固定版本处理数据']].map((x,i)=>`<div class="path-step"><span class="path-num">${x[0]}</span><span><strong>${x[1]}</strong><div class="row-sub">${x[2]}</div></span>${i<2?ic('arrow',16):''}</div>`).join('')}</div></section>`}
function downloadFile(name,content,mime='text/csv;charset=utf-8'){const url=URL.createObjectURL(new Blob(['\uFEFF'+content],{type:mime})),a=document.createElement('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),3000)}
function renderSettings(){
 return `${pageHead('','模型连接','管理模型服务地址、模型 ID 和凭据，并测试模型是否可调用。')}
 <div id="model-connection-page"></div>`;
}
function render(){
 router.write(ui,true);
 const isKnowledge=ui.page==='knowledge'||ui.page==='kbDetail';
 const isMethod=['methods','methodDetail'].includes(ui.page);
 const renderPage={overview:renderOverview,
   debug:()=>debug.page(methodChoices,methodOptionsError),batch:batch.listPage,batchWizard:()=>batch.wizard(methodChoices),taskDetail:batch.taskPage,settings:renderSettings};
 const content=isKnowledge?'<div id="knowledge-page"></div>':isMethod?'<div id="method-page"></div>':renderPage[ui.page]();
 $('#app').innerHTML=navbar()+'<main class="main">'+topbar()+'<div class="workspace">'+content+'</div></main>';
 if(isKnowledge)knowledge.mount($('#knowledge-page'),ui,goto,toast);
 if(isMethod)methods.mount($('#method-page'),ui,{navigate:goto,notify:toast,refreshOptions:refreshMethodOptions,
   writeRoute:replace=>router.write(ui,replace)},{esc,ic,badge,pageHead,empty,note});
 if(ui.page==='settings')models.mount($('#model-connection-page'),toast,()=>{});
 else models.unmount();
 renderModal();
}
function renderModal(){const root=$('#overlay');if(!root)return;if(!ui.modal){root.innerHTML='';return}const v=ui.modalValues,t=ui.modal;let title='',body='',footer='',wide=false,drawer=false;const actionBtn=(label,act,cls='primary')=>btn(label,act,cls),cancel=btn('取消','modalClose');const mf=(label,key,opts={})=>field(label,v[key]??'','modal:'+key,opts);switch(t){
case 'help':title='使用说明';body=`${note('单篇调试和批量识别使用已保存的方法、知识库和模型配置。批量任务提交后由后台执行。')}<div class="divider"></div><div class="section-title">建议体验路径</div><p class="smalltext muted" style="line-height:1.9">识别方法 → 选择阶段 → 创建草稿 → 保存新版本 → 单篇调试 → 批量识别 → 查看任务。知识库拥有独立版本，方法版本只引用固定知识版本。</p>`;footer=actionBtn('明白了','modalClose');break;
case 'globalSearch':title='全局搜索';body=`<label class="searchbox" style="max-width:none">${ic('search',17)}<input data-bind="modal:q" placeholder="输入方法、知识库或任务关键词" value="${esc(v.q||'')}" autofocus/></label><div class="hint mt12">请选择目标类别，进入对应列表进行检索。</div><div class="action-row mt18">${actionBtn('识别方法','globalTo:methods','secondary')}${actionBtn('知识库','globalTo:knowledge','secondary')}${actionBtn('批量任务','globalTo:batch','secondary')}</div>`;footer=cancel;break;
case 'batchItem':title='论文识别快照';drawer=true;body=batch.itemView();footer=actionBtn('关闭','modalClose');break;
case 'debugHistory':title='调试历史';drawer=true;body=debug.historyView();footer=actionBtn('关闭','modalClose');break;
case 'debugCandidate':title='临时加入候选学科';body=debug.candidateView();footer=cancel+actionBtn('添加并执行','debug:applyCandidate');break;
default:title='操作预览';body=note('这一操作在原型中以交互示意呈现。');footer=actionBtn('关闭','modalClose')}
root.innerHTML=`<div class="${drawer?'drawer-overlay':'modal-overlay'}" data-overlay="1" role="presentation"><section class="${drawer?'drawer':'modal '+(wide?'wide':'')}" role="dialog" aria-modal="true" aria-label="${esc(title)}"><div class="modal-header"><strong>${esc(title)}</strong><button class="icon-button" data-action="modalClose" title="关闭">${ic('close',18)}</button></div><div class="modal-body">${body}</div><div class="modal-footer">${footer}</div></section></div>`;}
function openMethod(id,version){
 const m=methodChoices.find(row=>row.id===id),v=m?.versions.find(row=>row.number===version);
 if(!m){toast('找不到可查看的识别方法',true);return false}
 return goto('methodDetail',{methodId:id,methodTab:'flow',methodStage:'router',methodVersion:v?String(v.id):'',methodView:v?'version':''});
}
function startDebug(id,version){
 const m=methodChoices.find(m=>m.id===id)||methodChoices.find(m=>m.versions.length);
 if(!m?.versions.length){toast('请先将方法草稿保存为新版本',true);return false}
 return goto('debug',{dbgMethodId:m.id,dbgVersion:getMver(m,version).number,dbgRunId:'',dbgHistoryId:''});
}
function copyToDebug(run){
 if(startDebug(String(run.methodId),'v'+run.methodVersionNo))debug.reset(run.input,run.id);
}
function action(a){
 const [prefix,operation,value]=a.split(':');
 if(prefix==='nav'){goto(operation);return}
 if(prefix==='globalTo'){
  const q=ui.modalValues.q||'';closeModal();
  if(operation==='methods')methods.search(q);else if(operation==='knowledge')knowledge.search(q);else batch.search(q);
  goto(operation);return;
 }
 if(prefix==='methodOpen'){openMethod(operation);return}
 if(prefix==='debug'){debug.handle(operation,value,methodChoices);return}
 if(prefix==='batch'){batch.handle(operation,value,methodChoices);return}
 switch(a){
case 'noop':break;
case 'methodNew':methods.newOnMount();goto('methods');break;
case 'modalClose':closeModal();break;
case 'help':openModal('help');break;
case 'globalSearch':openModal('globalSearch',{q:''});break;
case 'globalTo:methods':case 'globalTo:knowledge':case 'globalTo:batch':break;
default:toast('该操作已预留对应的界面入口');}
}
// 工作区导航及单篇、批量交互。
document.addEventListener('change',e=>{
 const el=e.target,bind=el.dataset.bind,filter=el.dataset.filter;
 if(el.hasAttribute?.('data-batch-file')){batch.setFile(el.files?.[0]);return}
 if(filter){const value=el.value;switch(filter){case 'dbgMethod':{const m=methodChoices.find(m=>m.id===value);ui.dbgMethodId=value;ui.dbgVersion=m.defaultVersion;break}case 'dbgVersion':ui.dbgVersion=value;break;}render();return}
 if(!bind)return;const parts=bind.split(':'),section=parts[0],key=parts[1],val=el.type==='checkbox'?el.checked:el.type==='number'?(el.value===''?'':Number(el.value)):el.value;
 if(section==='modal'){ui.modalValues[key]=val;return}
 if(section==='debug'){if(debug.editable())ui.dbgInput[key]=val;return}
 if(section==='batch'){
  batch.bind(bind.slice(6),val,true);
  if(key==='methodId')batch.handle('methodChoice',null,methodChoices);
 }
});
document.addEventListener('input',e=>{
 const bind=e.target.dataset.bind;
 if(bind?.startsWith('debug:')&&debug.editable())ui.dbgInput[bind.slice(6)]=e.target.value;
 if(bind?.startsWith('modal:'))ui.modalValues[bind.slice(6)]=e.target.value;
 if(['batch:name','batch:listSearch'].includes(bind))batch.bind(bind.slice(6),e.target.value,false);
 const search=e.target.dataset.search;if(!search)return;const cursor=e.target.selectionStart,val=e.target.value;ui[search]=val;render();const replacement=document.querySelector(`[data-search="${search}"]`);if(replacement){replacement.focus();try{replacement.setSelectionRange(cursor,cursor)}catch{}}});
document.addEventListener('click',e=>{const a=e.target.closest('[data-action]');if(a){e.preventDefault();action(a.dataset.action,e);return}if(e.target.matches('[data-overlay]'))closeModal()});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();openModal('globalSearch',{q:''})}if(e.key==='Escape'&&!models.dismiss()&&!methods.dismiss()&&ui.modal){closeModal()}});
window.addEventListener('beforeunload',e=>{if(methods.dirty()){e.preventDefault();e.returnValue=''}});
function restoreRoute(){
 const route=router.read();
 if(route.page==='debug')route.params={dbgMethodId:'',dbgVersion:'',dbgRunId:'',dbgHistoryId:'',...route.params};
 const params=route.page==='methodDetail'?{methodId:'',methodVersion:'',methodView:'',methodTab:'flow',methodStage:'router',...route.params}:route.params;
 const page=route.page==='taskDetail'&&!/^\d+$/.test(params.batchTaskId||'')?'batch':route.page;
 if(!goto(page,params,true))router.write(ui,true);
}
window.addEventListener('popstate',restoreRoute);
window.addEventListener('hashchange',restoreRoute);
function selectDebugVersion(){
 const m=methodChoices.find(row=>row.id===ui.dbgMethodId)||methodChoices[0];
 if(m){ui.dbgMethodId=m.id;ui.dbgVersion=getMver(m,ui.dbgVersion).number;}
}
async function refreshMethodOptions(){
 const stamp=++methodOptionsToken;
 try{
  const rows=await methods.options();
  if(stamp!==methodOptionsToken)return;
  methodChoices=rows.map(row=>{
   const versions=row.versions.map(v=>({...v,number:'v'+v.versionNo,knowledgeVersion:v.knowledgeVersionNo==null?'':'v'+v.knowledgeVersionNo}));
   const preferred=versions.find(v=>v.id===(row.defaultVersionId||row.latestVersionId))||versions[0];
   return {...row,id:String(row.id),versions,defaultVersion:preferred?.number||''};
  });
  methodOptionsError='';
 }catch(error){
  if(stamp!==methodOptionsToken)return;
  methodChoices=[];methodOptionsError=error.message;
 }
 if(ui.page==='debug'&&!ui.dbgRunId&&!ui.dbgHistoryId)selectDebugVersion();
 if(!['methodDetail','methods','knowledge','kbDetail','settings'].includes(ui.page)&&!ui.modal)render();
}
const debug = window.ClassifierDebug.create(ui, {
 render, notify:toast, openModal, closeModal, renderModal, openMethod, download:downloadFile
}, {esc,btn,badge,field,opt,note,ic,empty,pageHead});
const batch = window.ClassifierBatch.create(ui, {
 render, notify:toast, goto, openModal, closeModal, renderModal, openMethod, copyToDebug,
 renderEvidence:debug.renderEvidence, download:downloadFile
}, {esc,btn,badge,field,note,pageHead,empty});
restoreRoute();
refreshMethodOptions();
fetch('/api/auth/me',{credentials:'same-origin'}).then(response=>response.ok?response.json():null).then(result=>{
 if(result?.success&&typeof result.data?.preferences?.advancedConfig==='boolean')methods.preferAdvanced(result.data.preferences.advancedConfig);
}).catch(()=>{});
})();

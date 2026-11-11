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
const now=()=>new Date().toLocaleString('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
const {seedData} = window.ClassifierMockData;
const knowledge = window.ClassifierKnowledge;
const models = window.ClassifierModelConnections;
const methods = window.ClassifierMethods;
const store = window.ClassifierMockStore;
const data = store.load(seedData);
let methodChoices = [];
let methodOptionsError = '';
let methodOptionsToken = 0;
const ui={page:'overview',methodId:'',methodTab:'flow',methodStage:'router',methodView:'',methodVersion:'',kbId:'',kbTab:'profiles',kbSelected:'',kbVersion:'',dbgMethodId:'',dbgVersion:'',dbgInput:{type:'学术论文',title:'',keywords:'',abstract:'',journal:'',doi:'',year:'',externalId:''},dbgMore:false,dbgRunId:'',dbgHistoryId:'',batchSearch:'',batchStatus:'all',batchTaskId:'t1',batchStep:1,batchForm:{name:'',methodId:'',version:'',fileName:'',rows:[],columns:[],mapping:{id:'编号',title:'标题',abstract:'摘要',keywords:'关键词'},skipInvalid:false},modal:null,modalValues:{},pending:null};
const persist=()=>{if(!store.save(data))toast('本地存储不可用或已满，本次修改仅保留在当前页面',true)};
const toast=(s,error=false)=>{const d=document.createElement('div');d.className='toast'+(error?' error':'');d.textContent=s;$('#toasts').append(d);setTimeout(()=>d.remove(),3300)};
const getMver=(m,num)=>m?.versions.find(v=>v.number===num)||m?.versions.find(v=>v.number===m.defaultVersion)||m?.versions[0];
const mName=(id,snapshot)=>snapshot||methodChoices.find(m=>m.id===id)?.name||'历史演示方法';
const router = window.ClassifierRouter;
const goto=(page,params={},replace=false)=>{
 if(!router.has(page))page='overview';
 if(methods.guard(page,params,()=>goto(page,params,replace)))return false;
 if(['methods','methodDetail'].includes(ui.page)&&!['methods','methodDetail'].includes(page))methods.unmount();
 ui.page=page;Object.assign(ui,params);
 if(page==='debug'&&!ui.dbgRunId&&!ui.dbgHistoryId)selectDebugVersion();
 router.write(ui,replace);render();
 if(page==='debug')debug.route();
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
const selectFrom=(id,arr,val,label='')=>`<select class="filter-select" id="${id}" data-filter="${id}" title="${label}">${opt(arr,val)}</select>`;
const navButton=(title,page,icon)=>`<button class="tile" data-action="nav:${page}"><span class="tile-icon">${ic(icon,21)}</span><span class="grow"><div class="tile-title">${title}</div><div class="tile-desc">${({debug:'使用已保存的方法版本，逐阶段观察识别结果',batch:'上传数据并运行批量任务，查看进度与导出结果',methods:'配置识别流程、阶段规则与模型，管理版本'})[page]}</div></span><span class="tile-arrow">${ic('arrow',17)}</span></button>`;
function renderOverview(){const recent=methodChoices.slice(0,3),tasks=data.tasks.slice(0,3);return `${pageHead('WORKSPACE / OVERVIEW','工作台总览','管理识别方法、验证单篇结果并执行批量任务。所有操作均围绕固定的方法与知识库版本进行。',`${btn(ic('plus',15)+' 新建方法','methodNew')}${btn(ic('play',15)+' 开始单篇调试','nav:debug','primary')}`)}<div class="tiles">${navButton('单篇调试','debug','flask')}${navButton('批量识别','batch','layers')}${navButton('识别方法','methods','workflow')}</div><div class="section-head section-space"><h2 class="section-title">继续工作</h2><span class="section-sub">快速回到最近操作的内容</span></div><div class="split-2"><section class="panel"><div class="panel-head"><span class="panel-title">最近使用的方法</span>${btn('查看全部 '+ic('arrow',13),'nav:methods','ghost small')}</div>${recent.map(m=>`<div class="row-item"><span class="tile-icon" style="width:35px;height:35px;border-radius:8px">${ic('workflow',18)}</span><div class="grow" style="min-width:0"><div class="row-name">${esc(m.name)}</div><div class="row-sub">${badge(m.defaultVersion,'green')}　知识库：${esc(getMver(m)?.knowledgeName)} ${esc(getMver(m)?.knowledgeVersion)}</div></div><button class="icon-button" data-action="methodOpen:${m.id}" title="打开方法">${ic('arrow',17)}</button></div>`).join('')}</section><section class="panel"><div class="panel-head"><span class="panel-title">最近任务</span>${btn('查看全部 '+ic('arrow',13),'nav:batch','ghost small')}</div>${tasks.map(t=>`<div class="row-item"><span class="tile-icon" style="width:35px;height:35px;border-radius:8px">${ic(t.status==='已完成'?'checkcircle':'layers',18)}</span><div class="grow" style="min-width:0"><div class="row-name">${esc(t.name)}</div><div class="row-sub">${esc(mName(t.methodId,t.methodName))} ${esc(t.version)} · ${t.success}/${t.total} 条${t.status==='处理中'?`<div class="progress mt8"><span style="width:${t.total?(t.success/t.total*100):0}%"></span></div>`:''}</div></div>${badge(t.status,t.status==='已完成'?'green':t.status==='需检查'?'amber':'neutral')}<button class="icon-button" data-action="taskOpen:${t.id}" title="查看任务">${ic('arrow',17)}</button></div>`).join('')}</section></div><section class="panel section-space"><div class="path-row"><span class="bold" style="font-size:13px">推荐使用路径</span>${[['1','配置方法','定义规则与模型'],['2','单篇验证','检查阶段结果'],['3','批量识别','使用固定版本处理数据']].map((x,i)=>`<div class="path-step"><span class="path-num">${x[0]}</span><span><strong>${x[1]}</strong><div class="row-sub">${x[2]}</div></span>${i<2?ic('arrow',16):''}</div>`).join('')}</div></section><p class="hint mt12">识别方法来自业务库。批量任务仍使用演示结果。</p>`}
const previewRows=(task)=>task.rows?.length?task.rows:[{编号:'P-001',标题:'面向科研文本的跨学科知识整合识别',摘要:'采用语义分析与领域知识融合',关键词:'语义；知识整合',_status:'成功',_subject:'信息资源管理',_score:'0.63'},{编号:'P-002',标题:'多模态知识抽取及其学科映射',摘要:'跨模态内容识别',关键词:'多模态',_status:task.failed?'失败':'成功',_subject:'计算机科学与技术',_score:'0.51'},{编号:'P-003',标题:'创新网络中的知识传播机制',摘要:'研究产业网络和知识溢出',关键词:'知识网络',_status:'成功',_subject:'管理科学与工程',_score:'0.47'}];
const makeCSV=(rows)=>rows.map(r=>r.map(x=>'"'+String(x??'').replace(/"/g,'""')+'"').join(',')).join('\r\n');
function downloadFile(name,content,mime='text/csv;charset=utf-8'){const url=URL.createObjectURL(new Blob(['\uFEFF'+content],{type:mime})),a=document.createElement('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),3000)}
function renderBatch(){const shown=data.tasks.filter(t=>(ui.batchStatus==='all'||t.status===ui.batchStatus)&&(!ui.batchSearch||t.name.includes(ui.batchSearch)));return `${pageHead('WORKSPACE / BATCH','批量识别','按方法版本创建任务，上传数据、检查字段映射，并查看执行过程与结果。',btn(ic('plus',15)+' 新建批量任务','batchNew','primary'))}<div class="toolbar"><label class="searchbox">${ic('search',16)}<input data-search="batchSearch" value="${esc(ui.batchSearch)}" placeholder="搜索任务名称"/></label><div class="toolbar-right">${selectFrom('batchStatus',[['all','全部状态'],'处理中','已完成','需检查','待处理'],ui.batchStatus)}</div></div><section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th style="width:26%">任务名称</th><th>方法 / 版本</th><th>提交时间</th><th>总数</th><th>成功 / 失败 / 待处理</th><th>状态与进度</th><th>操作</th></tr></thead><tbody>${shown.map(t=>`<tr><td><div class="table-main">${esc(t.name)}</div><div class="table-desc">${esc(t.file)}</div></td><td>${esc(mName(t.methodId,t.methodName))}<div class="hint">${esc(t.version)} · ${esc(t.kbVersion)}</div></td><td class="muted">${esc(t.date)}</td><td class="mono">${t.total}</td><td><strong class="mono">${t.success}</strong> / <span class="text-danger mono">${t.failed}</span> / <span class="mono">${t.pending}</span></td><td>${badge(t.status,t.status==='已完成'?'green':t.status==='需检查'?'amber':'neutral')}<div class="progress mt8" style="width:120px"><span style="width:${t.total?Math.round((t.success+t.failed)/t.total*100):0}%"></span></div></td><td><div class="method-actions">${btn('查看详情','taskOpen:'+t.id,'small')}${btn('导出','taskExport:'+t.id,'small ghost')}${t.failed?btn('重试失败项','taskRetry:'+t.id,'small ghost'):''}</div></td></tr>`).join('')}</tbody></table></div>${shown.length?'':empty('没有匹配的任务','修改筛选条件，或新建一项批量任务。',btn('新建任务','batchNew','secondary'))}</section>`}
const batchMockRows=()=>[{编号:'P-001',标题:'大语言模型与学科交叉识别',摘要:'探讨科研文本中的跨学科语义要素。',关键词:'学科交叉；大语言模型'},{编号:'P-002',标题:'信息检索与科学知识组织',摘要:'提出基于知识组织的检索框架。',关键词:'知识组织；信息检索'},{编号:'P-003',标题:'',摘要:'缺少标题字段的示例行。',关键词:'示例'},{编号:'P-001',标题:'重复编号示例',摘要:'用于演示上传检查。',关键词:'异常样例'}];
const csvParse=text=>{const out=[],row=[];let cur='',q=false;for(let i=0;i<text.length;i++){const c=text[i];if(c==='"'){if(q&&text[i+1]==='"'){cur+='"';i++}else q=!q}else if(c===','&&!q){row.push(cur);cur=''}else if((c==='\n'||c==='\r')&&!q){if(c==='\r'&&text[i+1]==='\n')i++;row.push(cur);if(row.some(s=>s.trim()))out.push([...row]);row.length=0;cur=''}else cur+=c}row.push(cur);if(row.some(s=>s.trim()))out.push(row);const headers=(out.shift()||[]).map((h,i)=>(i===0?h.replace(/^\uFEFF/,''):h).trim());return {columns:headers,rows:out.map(r=>Object.fromEntries(headers.map((h,i)=>[h,r[i]||''])))};};
function checkBatchRows(){const f=ui.batchForm,seen=new Set();return f.rows.map((r,i)=>{const id=String(r[f.mapping.id]||'').trim(),title=String(r[f.mapping.title]||'').trim(),abstract=String(r[f.mapping.abstract]||'').trim();const errs=[];if(!title&&!abstract)errs.push('必要文本为空');if(!id)errs.push('缺少编号');if(id&&seen.has(id))errs.push('编号重复');if(id)seen.add(id);return {index:i+1,row:r,error:errs.join('；')}})}
function renderBatchWizard(){
 const f=ui.batchForm;
 const m=methodChoices.find(m=>m.id===f.methodId)||methodChoices[0];
 const v=getMver(m,f.version);
 if(!v)return pageHead('','新建批量任务','选择已保存的方法版本。')+empty('暂无可选方法',esc(methodOptionsError||'请先保存一个识别方法版本。'),btn('前往识别方法','nav:methods'));
 f.methodId=m.id;f.version=v.number;
 const checks=checkBatchRows(),bad=checks.filter(c=>c.error),good=checks.filter(c=>!c.error);
 const steps=['选择方法','上传与检查','确认提交'];
 const stepper=`<div class="wizard-steps">${steps.map((s,i)=>`${i?'<div class="wizard-sep"></div>':''}<div class="wizard-step ${ui.batchStep===i+1?'active':ui.batchStep>i+1?'done':''}"><span class="n">${ui.batchStep>i+1?ic('check',13):i+1}</span>${s}</div>`).join('')}</div>`;
 let content='';
 if(ui.batchStep===1){
  content=`<div class="section-head"><strong class="section-title">1. 任务与方法</strong>${badge('运行依据将被固定','green')}</div>
  ${field('任务名称',f.name,'batch:name',{placeholder:'例如：2026 年研究生论文摘要识别'})}
  ${field('已保存的识别方法',f.methodId,'batch:methodId',{type:'select',options:methodChoices.filter(m=>m.versions.length).map(m=>[m.id,m.name])})}
  ${field('方法版本',f.version,'batch:version',{type:'select',options:m.versions.map(x=>x.number)})}
  <div class="inline-note">${ic('link',16)}<span>引用知识库：<strong>${esc(v.knowledgeName)} ${esc(v.knowledgeVersion)}</strong>。该关联随方法版本固定，批量任务不单独覆盖。</span></div>`;
 }else if(ui.batchStep===2){
  const upload=`<div class="section-head"><strong class="section-title">2. 上传文件与检查</strong>${btn(ic('download',14)+' 下载 CSV 模板','batchTemplate','small')}</div>
  <div class="upload-area">${ic('upload',26)}<strong>${f.fileName?esc(f.fileName):'选择 CSV 文件上传'}</strong><span class="smalltext muted">支持 UTF-8 编码 CSV；文件只在当前浏览器内解析，不会上传到服务器</span><input type="file" id="batch-file" accept=".csv,text/csv"/><span style="pointer-events:none">${btn('浏览文件','noop','small secondary')}</span></div>
  <div class="action-row mt12">${btn('载入示例数据','batchSample','small ghost')}${f.rows.length?badge('已加载 '+f.rows.length+' 行','green'):''}</div>`;
  let checksHtml='';
  if(f.rows.length){
   const fields=[['id','编号'],['title','标题'],['abstract','摘要'],['keywords','关键词']];
   checksHtml=`<div class="section-head section-space"><strong>字段映射</strong><span class="section-sub">确认输入文件列与业务字段的对应关系</span></div>
   <div class="field-grid three">${fields.map(z=>field(z[1],f.mapping[z[0]],'batch:map:'+z[0],{type:'select',options:[['','未映射'],...f.columns.map(c=>[c,c])]})).join('')}</div>
   <div class="section-head section-space"><strong>检查结果</strong><span>${badge(good.length+' 有效','green')} ${badge(bad.length+' 异常',bad.length?'amber':'neutral')}</span></div>
   <div class="table-wrap"><table class="data-table"><thead><tr><th>行号</th><th>外部编号</th><th>标题 / 内容</th><th>检查结果</th></tr></thead><tbody>
   ${checks.slice(0,8).map(c=>`<tr><td>${c.index}</td><td>${esc(c.row[f.mapping.id])}</td><td>${esc(c.row[f.mapping.title]||String(c.row[f.mapping.abstract]||'').slice(0,35))}</td><td>${c.error?badge(c.error,'red'):badge('有效','green')}</td></tr>`).join('')}
   </tbody></table></div>${checks.length>8?`<p class="hint">仅预览前 8 行，提交时将检查全部数据。</p>`:''}`;
   if(bad.length)checksHtml+=`<label class="checkbox-line mt18"><input type="checkbox" data-bind="batch:skipInvalid" ${f.skipInvalid?'checked':''}/> 明确跳过 ${bad.length} 条异常数据（可导出异常清单）</label><div class="action-row mt12">${btn('导出异常清单','batchInvalidExport','small')}</div>`;
  }else checksHtml=note('请先上传 CSV 文件，或载入演示数据。');
  content=upload+checksHtml;
 }else{
  content=`<div class="section-head"><strong class="section-title">3. 确认提交</strong>${badge('创建后生成独立任务记录','green')}</div>
  <div class="stat-grid">${[['任务名称',f.name||'未命名'],['有效记录',String(good.length)+' 条'],['异常记录',String(bad.length)+' 条'],['方法版本',v.number]].map(x=>`<div class="stat-card"><div class="caption">${x[0]}</div><div class="value" style="font-size:17px">${esc(x[1])}</div></div>`).join('')}</div>
  <div class="divider"></div><div class="field-grid"><div class="field"><div class="label">识别方法</div><strong>${esc(m.name)} ${esc(v.number)}</strong></div><div class="field"><div class="label">知识库快照</div><strong>${esc(v.knowledgeName)} ${esc(v.knowledgeVersion)}</strong></div><div class="field"><div class="label">输入文件</div>${esc(f.fileName)}</div><div class="field"><div class="label">异常处理</div>${bad.length?(f.skipInvalid?'跳过异常行，保留导出清单':'不允许提交'):'全部通过'}</div></div>
  ${note('此原型仅创建本地任务快照。真实队列调度、模型调用与大规模文件解析需要后端服务。')}`;
 }
 return `<div class="mini-nav"><a data-action="nav:batch">批量识别</a>${ic('chevron',13)}新建任务</div>
 ${pageHead('BATCH / NEW TASK','新建批量任务','三步完成方法选择、数据校验和任务提交。提交后固定方法版本及其知识库版本。',btn('返回任务列表','nav:batch'))}
 ${stepper}<section class="panel panel-pad" style="max-width:1030px">${content}</section>
 <div class="wizard-footer" style="max-width:1030px">${btn(ui.batchStep===1?'取消':'上一步',ui.batchStep===1?'nav:batch':'batchPrev')}${btn(ui.batchStep===3?'确认创建任务':'下一步',ui.batchStep===3?'batchSubmit':'batchNext','primary')}</div>`;
}
function renderTaskDetail(){const t=data.tasks.find(t=>t.id===ui.batchTaskId)||data.tasks[0],rows=previewRows(t),m=methodChoices.find(m=>m.id===t.methodId);return `<div class="mini-nav"><a data-action="nav:batch">批量识别</a>${ic('chevron',13)}<span>${esc(t.name)}</span></div>${pageHead('BATCH / TASK DETAIL',esc(t.name),'查看固定运行依据、执行状态、逐条结果与失败原因。',`${btn('导出成功结果','taskExport:'+t.id)}${btn('重试失败项','taskRetry:'+t.id,'secondary')}${t.status==='待处理'?btn('模拟执行','taskSimulate:'+t.id,'primary'):''}`)}<div class="meta-strip"><span>方法 <a class="text-green" data-action="taskMethod" style="cursor:pointer">${esc(mName(t.methodId,t.methodName))}</a> <strong>${esc(t.version)}</strong></span><span>知识库 <strong>${esc(t.kbVersion)}</strong></span><span>原文件 <strong>${esc(t.file)}</strong></span><span>提交时间 <strong>${esc(t.date)}</strong></span>${badge(t.status,t.status==='已完成'?'green':t.status==='需检查'?'amber':'neutral')}</div><div class="stat-grid">${[['总数',t.total],['成功',t.success],['失败',t.failed],['待处理',t.pending]].map((x,i)=>`<div class="stat-card"><div class="caption">${x[0]}</div><div class="value" style="${i===2&&t.failed?'color:var(--red)':''}">${x[1]}</div></div>`).join('')}</div><section class="panel panel-pad mt18"><div class="section-head"><strong>执行进度</strong><span class="muted smalltext">已处理 ${t.success+t.failed} / ${t.total} 条</span></div><div class="progress" style="height:7px"><span style="width:${t.total?100*(t.success+t.failed)/t.total:0}%"></span></div><div class="hint mt12">进度与任务数据在此原型中为演示状态，不代表真实服务执行。</div></section><div class="section-head section-space"><h2 class="section-title">结果明细</h2><div class="card-actions">${btn('导出失败清单','taskFailureExport:'+t.id,'small')}${btn('重新调试单条结果','taskDebug:'+t.id,'small ghost')}</div></div><section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th>外部编号</th><th>标题 / 名称</th><th>主学科</th><th>其他相关学科</th><th>ICI</th><th>状态</th><th>操作</th></tr></thead><tbody>${rows.slice(0,12).map((r,i)=>{const status=r._status||((i===1&&t.failed)?'失败':'成功');return `<tr><td class="mono">${esc(r.编号||r.id||'P-'+(i+1))}</td><td class="bold">${esc(r.标题||r.title||'未命名科研文本')}</td><td>${status==='成功'?esc(r._subject||'信息资源管理'):'—'}</td><td class="muted">${status==='成功'?'计算机科学与技术、管理科学与工程':'—'}</td><td class="mono">${status==='成功'?esc(r._score||'0.63'):'—'}</td><td>${badge(status,status==='成功'?'green':'red')}</td><td>${btn('查看快照','taskRow:'+i,'small')}${btn('单篇调试','taskDebugRow:'+i,'small ghost')}</td></tr>`}).join('')}</tbody></table></div></section><div class="hint mt12">${t.rows?.length?'当前任务显示当前浏览器中创建的记录；':'当前历史任务为模拟记录，只展示少量示例行，并非完整结果集。'}查看快照不会触发重算，重新调试将创建新的单篇调试过程。</div>`}
function renderSettings(){
 return `${pageHead('','模型连接','管理模型服务地址、模型 ID 和凭据，并测试模型是否可调用。')}
 <div id="model-connection-page"></div>`;
}
function render(){
 router.write(ui,true);
 const isKnowledge=ui.page==='knowledge'||ui.page==='kbDetail';
 const isMethod=['methods','methodDetail'].includes(ui.page);
 const renderPage={overview:renderOverview,
   debug:()=>debug.page(methodChoices,methodOptionsError),batch:renderBatch,batchWizard:renderBatchWizard,taskDetail:renderTaskDetail,settings:renderSettings};
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
case 'help':title='原型使用说明';body=`${note('识别方法、知识库和模型连接使用业务库。单篇调试与批量执行仍是演示。')}<div class="divider"></div><div class="section-title">建议体验路径</div><p class="smalltext muted" style="line-height:1.9">识别方法 → 选择阶段 → 创建草稿 → 保存新版本 → 单篇调试 → 批量识别 → 查看任务。知识库拥有独立版本，方法版本只引用固定知识版本。</p><p class="smalltext muted">演示任务和调试记录仅保存在当前浏览器。</p>`;footer=actionBtn('明白了','modalClose');break;
case 'globalSearch':title='全局搜索';body=`<label class="searchbox" style="max-width:none">${ic('search',17)}<input data-bind="modal:q" placeholder="输入方法、知识库或任务关键词" value="${esc(v.q||'')}" autofocus/></label><div class="hint mt12">请选择目标类别，进入对应列表进行检索。</div><div class="action-row mt18">${actionBtn('识别方法','globalTo:methods','secondary')}${actionBtn('知识库','globalTo:knowledge','secondary')}${actionBtn('批量任务','globalTo:batch','secondary')}</div>`;footer=cancel;break;
case 'taskRow':title='原任务结果快照';body=`${note('这是任务提交时记录的快照展示。查看不会重算，也不会修改原任务。')}<div class="divider"></div><div class="label">论文标题</div><strong>${esc(v.title)}</strong><div class="label mt18">主学科 / 交叉度</div><div>${esc(v.subject)} · ${esc(v.score)}</div><div class="hint mt18">方法版本：${esc(v.version)}；知识库版本：${esc(v.kbVersion)}</div>`;footer=cancel+actionBtn('复制到单篇调试','taskRowToDebug','secondary');break;
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
function startBatch(id,version){
 const m=methodChoices.find(m=>m.id===id&&m.versions.length)||methodChoices.find(m=>m.versions.length);
 if(!m){toast('请先保存一个方法版本',true);return false}
 return goto('batchWizard',{batchForm:{name:'',methodId:m.id,version:getMver(m,version).number,fileName:'',rows:[],columns:[],mapping:{id:'编号',title:'标题',abstract:'摘要',keywords:'关键词'},skipInvalid:false},batchStep:1});
}
function getTaskRow(t,i){const rows=previewRows(t);return rows[i]||rows[0]}
function action(a,e){let id,parts=a.split(':'),prefix=parts[0],suffix=parts.slice(1).join(':');if(prefix==='nav'){goto(suffix);return}if(prefix==='globalTo'){const q=ui.modalValues.q||'';closeModal();if(suffix==='methods')methods.search(q);else if(suffix==='knowledge')knowledge.search(q);else ui.batchSearch=q;goto(suffix);return}if(prefix==='methodOpen'){openMethod(suffix);return}if(prefix==='debug'){const [operation,value]=parts.slice(1);debug.handle(operation,value,methodChoices);return}if(prefix==='taskOpen'){ui.batchTaskId=suffix;goto('taskDetail');return}if(prefix==='taskExport'){const t=data.tasks.find(z=>z.id===suffix),rr=previewRows(t);downloadFile('batch_'+suffix+'_results.csv',makeCSV([['编号','标题','主学科','交叉度','状态'],...rr.filter(r=>r._status==='成功').map(r=>[r.编号||r.id,r.标题||r.title,r._subject||'',r._score??'',r._status])]));toast('已导出演示结果 CSV');return}if(prefix==='taskFailureExport'){const t=data.tasks.find(z=>z.id===suffix);downloadFile('batch_'+suffix+'_failures.csv',makeCSV([['编号','错误原因'],...previewRows(t).filter(r=>r._status==='失败').map(r=>[r.编号||r.id,'模拟处理失败'])]));toast('失败清单已导出（演示行）');return}if(prefix==='taskRetry'){const t=data.tasks.find(z=>z.id===suffix);if(!t.failed){toast('当前没有失败项');return}t.success+=t.failed;t.failed=0;t.pending=Math.max(0,t.total-t.success);t.status=t.pending?'处理中':'已完成';t.rows?.forEach(r=>{if(r._status==='失败')r._status='成功'});persist();render();toast('已模拟重试失败项（未调用后端）');return}if(prefix==='taskSimulate'){const t=data.tasks.find(z=>z.id===suffix);t.success=t.total;t.failed=0;t.pending=0;t.status='已完成';t.rows?.forEach(r=>{r._status='成功';r._subject='信息资源管理';r._score='0.63'});persist();render();toast('已完成模拟任务（未执行模型推理）');return}if(prefix==='taskRow'){const t=data.tasks.find(z=>z.id===ui.batchTaskId),r=getTaskRow(t,Number(suffix));openModal('taskRow',{title:r.标题||r.title||'无标题',subject:r._subject||'信息资源管理',score:r._score||'0.63',version:t.version,kbVersion:t.kbVersion,index:Number(suffix)});return}if(prefix==='taskDebugRow'){const t=data.tasks.find(z=>z.id===ui.batchTaskId),r=getTaskRow(t,Number(suffix));startDebug(t.methodId,t.version);ui.dbgInput={...ui.dbgInput,title:r.标题||r.title||'',abstract:r.摘要||'',keywords:r.关键词||'',externalId:r.编号||''};render();return}
switch(a){
case 'noop':break;
case 'methodNew':methods.newOnMount();goto('methods');break;
case 'modalClose':closeModal();break;
case 'help':openModal('help');break;
case 'globalSearch':openModal('globalSearch',{q:''});break;
case 'globalTo:methods':case 'globalTo:knowledge':case 'globalTo:batch':break;
case 'batchNew':startBatch(ui.dbgMethodId);break;
case 'batchSample':ui.batchForm.columns=['编号','标题','摘要','关键词'];ui.batchForm.rows=batchMockRows();ui.batchForm.fileName='示例论文数据.csv';ui.batchForm.mapping={id:'编号',title:'标题',abstract:'摘要',keywords:'关键词'};render();toast('已载入含异常行的示例数据');break;
case 'batchTemplate':downloadFile('discipline_batch_template.csv',makeCSV([['编号','标题','摘要','关键词'],['P-001','示例科研论文','此处输入论文摘要','关键词一；关键词二']]));toast('已下载 CSV 模板');break;
case 'batchNext':{const f=ui.batchForm;if(ui.batchStep===1&&!f.name?.trim()){toast('请先填写任务名称',true);break}if(ui.batchStep===2){if(!f.rows.length){toast('请先上传数据文件',true);break}if(!f.mapping.id||(!f.mapping.title&&!f.mapping.abstract)){toast('必须映射编号，以及标题或摘要',true);break}const invalid=checkBatchRows().filter(x=>x.error);if(invalid.length&&!f.skipInvalid){toast(`有 ${invalid.length} 条异常记录，请修正或明确选择跳过`,true);break}if(checkBatchRows().every(x=>x.error)){toast('没有有效数据，无法提交',true);break}}ui.batchStep=Math.min(3,ui.batchStep+1);render();break}
case 'batchPrev':ui.batchStep=Math.max(1,ui.batchStep-1);render();break;
case 'batchInvalidExport':{const bad=checkBatchRows().filter(x=>x.error),f=ui.batchForm;downloadFile('invalid_rows.csv',makeCSV([['行号','编号','原因'],...bad.map(z=>[z.index,z.row[f.mapping.id],z.error])]));toast('异常清单已导出');break}
case 'batchSubmit':{const f=ui.batchForm,m=methodChoices.find(m=>m.id===f.methodId),v=getMver(m,f.version),checks=checkBatchRows(),valid=checks.filter(x=>!x.error);if(!valid.length){toast('没有可创建的有效任务',true);break}const t={id:'t'+Date.now(),name:f.name,methodId:m.id,methodName:m.name,version:v.number,kbVersion:v.knowledgeVersion,file:f.fileName,date:now(),total:valid.length,success:0,failed:0,pending:valid.length,status:'待处理',mapping:clone(f.mapping),methodSnapshot:clone(v),rows:valid.map(x=>({...x.row,编号:x.row[f.mapping.id]||'',标题:x.row[f.mapping.title]||'',摘要:x.row[f.mapping.abstract]||'',关键词:x.row[f.mapping.keywords]||'',_status:'待处理',_subject:'',_score:''}))};data.tasks.unshift(t);persist();ui.batchTaskId=t.id;goto('taskDetail');toast('本地模拟任务已创建。可点击「模拟执行」查看完成状态');break}
case 'taskMethod':{const t=data.tasks.find(x=>x.id===ui.batchTaskId);openMethod(t.methodId,t.version);break}
case 'taskDebug':{const t=data.tasks.find(x=>x.id===ui.batchTaskId);startDebug(t.methodId,t.version);break}
case 'taskRowToDebug':{const t=data.tasks.find(x=>x.id===ui.batchTaskId),r=getTaskRow(t,ui.modalValues.index);closeModal();startDebug(t.methodId,t.version);ui.dbgInput={...ui.dbgInput,title:r.标题||r.title||'',abstract:r.摘要||'',keywords:r.关键词||'',externalId:r.编号||''};render();break}
default:toast('该操作已预留对应的界面入口');}
}
// 工作区导航及单篇、批量演示交互。
document.addEventListener('change',e=>{
 const el=e.target,bind=el.dataset.bind,filter=el.dataset.filter;
 if(filter){const value=el.value;switch(filter){case 'dbgMethod':{const m=methodChoices.find(m=>m.id===value);ui.dbgMethodId=value;ui.dbgVersion=m.defaultVersion;break}case 'dbgVersion':ui.dbgVersion=value;break;case 'batchStatus':ui.batchStatus=value;break}render();return}
 if(!bind)return;const parts=bind.split(':'),section=parts[0],key=parts[1],val=el.type==='checkbox'?el.checked:el.type==='number'?(el.value===''?'':Number(el.value)):el.value;
 if(section==='modal'){ui.modalValues[key]=val;return}
 if(section==='debug'){if(debug.editable())ui.dbgInput[key]=val;return}
 if(section==='batch'){if(key==='map'){ui.batchForm.mapping[parts[2]]=val}else if(key==='methodId'){ui.batchForm.methodId=val;ui.batchForm.version=methodChoices.find(m=>m.id===val)?.defaultVersion||''}else ui.batchForm[key]=val;render();return}
});
document.addEventListener('input',e=>{
 const bind=e.target.dataset.bind;
 if(bind?.startsWith('debug:')&&debug.editable())ui.dbgInput[bind.slice(6)]=e.target.value;
 if(bind?.startsWith('modal:'))ui.modalValues[bind.slice(6)]=e.target.value;
 const search=e.target.dataset.search;if(!search)return;const cursor=e.target.selectionStart,val=e.target.value;ui[search]=val;render();const replacement=document.querySelector(`[data-search="${search}"]`);if(replacement){replacement.focus();try{replacement.setSelectionRange(cursor,cursor)}catch{}}});
document.addEventListener('click',e=>{const a=e.target.closest('[data-action]');if(a){e.preventDefault();action(a.dataset.action,e);return}if(e.target.matches('[data-overlay]'))closeModal()});
document.addEventListener('change',e=>{if(e.target.id!=='batch-file')return;const file=e.target.files?.[0];if(!file)return;const reader=new FileReader();reader.onload=()=>{try{const result=csvParse(reader.result);if(!result.columns.length)throw new Error('未识别到表头');ui.batchForm.fileName=file.name;ui.batchForm.columns=result.columns;ui.batchForm.rows=result.rows;const map={id:'编号',title:'标题',abstract:'摘要',keywords:'关键词'};const aliases={id:['编号','id','ID','paper_id','论文编号'],title:['标题','title','Title','论文标题'],abstract:['摘要','abstract','Abstract','内容说明'],keywords:['关键词','keywords','Keywords']};for(const k of Object.keys(map))map[k]=aliases[k].find(x=>result.columns.includes(x))||'';ui.batchForm.mapping=map;render();toast(`已解析 ${result.rows.length} 行数据（仅当前浏览器）`)}catch(err){toast('CSV 解析失败：'+err.message,true)}};reader.onerror=()=>toast('无法读取文件',true);reader.readAsText(file,'UTF-8')});
document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();openModal('globalSearch',{q:''})}if(e.key==='Escape'&&!models.dismiss()&&!methods.dismiss()&&ui.modal){closeModal()}});
window.addEventListener('beforeunload',e=>{if(methods.dirty()){e.preventDefault();e.returnValue=''}});
function restoreRoute(){
 const route=router.read();
 if(route.page==='debug')route.params={dbgMethodId:'',dbgVersion:'',dbgRunId:'',dbgHistoryId:'',...route.params};
 const params=route.page==='methodDetail'?{methodId:'',methodVersion:'',methodView:'',methodTab:'flow',methodStage:'router',...route.params}:route.params;
 const page=route.page==='taskDetail'&&!data.tasks.some(x=>x.id===params.batchTaskId)?'batch':route.page;
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
restoreRoute();
refreshMethodOptions();
fetch('/api/auth/me',{credentials:'same-origin'}).then(response=>response.ok?response.json():null).then(result=>{
 if(result?.success&&typeof result.data?.preferences?.advancedConfig==='boolean')methods.preferAdvanced(result.data.preferences.advancedConfig);
}).catch(()=>{});
})();

(function () {
'use strict';

const root = '/api/batch/tasks';
const active = status => ['queued','running'].includes(status);
const taskLabels = {draft:'草稿',queued:'等待执行',running:'执行中',succeeded:'全部成功',partial_failed:'部分失败',failed:'失败'};
const rowLabels = {pending:'待处理',running:'执行中',succeeded:'成功',failed:'失败',reused:'复用',skipped:'未执行'};
const phases = [['parse','输入解析'],['router','簇路由'],['recall','学科召回'],['judge','独立精判'],['post','后处理'],['result','学科结果']];
const mappings = [
  ['externalId','外部编号',['编号','外部编号','论文编号','id','paper_id','data_cgbh']],
  ['title','标题',['标题','题名','论文标题','title','data_title']],
  ['abstract','摘要',['摘要','内容说明','abstract','data_abstract']],
  ['keywords','关键词',['关键词','keywords','data_keywords']],
  ['journal','期刊 / 会议',['期刊','来源出版物','会议','journal','journal_name','data_journal']],
  ['year','出版信息',['出版年','年份','出版信息','year','pub_year','data_year']],
  ['doi','DOI',['doi','data_doi']],
  ['text','拼接原文',['拼接原文','data_cgmc']]
];
const day = value => value ? new Date(value).toLocaleString('zh-CN', {hour12:false}) : '—';
const query = parameters => '?' + new URLSearchParams(parameters);

async function request(path = '', method = 'GET', body) {
  const headers = {Accept:'application/json'};
  if (method !== 'GET') {
    const csrf = await fetch('/api/auth/csrf', {credentials:'same-origin'});
    const data = await csrf.json();
    if (!csrf.ok || !data.data?.csrfToken) throw new Error(data.message || '请求校验失效，请刷新页面');
    headers['X-CSRF-Token'] = data.data.csrfToken;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(root + path, {
    method, headers, credentials:'same-origin', body:body === undefined ? undefined : JSON.stringify(body)
  });
  const payload = await response.json();
  if (!response.ok || !payload.success) throw new Error(payload.message || '批量识别请求失败');
  return payload.data;
}

window.ClassifierBatch = Object.freeze({
  create(ui, actions, view) {
    const {render, notify, goto, openModal, closeModal, renderModal, openMethod, copyToDebug, renderEvidence, download} = actions;
    const {esc, btn, badge, field, note, pageHead, empty} = view;
    let recent = [], list = null, task = null, error = '', key = '', stamp = 0, poll = 0, busy = false;
    let listFilter = {search:'',status:'',page:1}, itemFilter = {status:'',discipline:'',page:1};
    let form = null, step = 1, item = null, itemError = '', trace = null, traceError = '', tracePhase = '', withCalls = false, itemStamp = 0;
    const redraw = () => { if (['batch','batchWizard','taskDetail','overview'].includes(ui.page)) render(); };
    const status = (value, labels = taskLabels) => badge(labels[value] || value,
      value === 'succeeded' ? 'green' : ['failed','partial_failed'].includes(value) ? 'amber' : 'neutral');
    const button = (text, action, style = 'small', disabled = false) => disabled
      ? `<button type="button" class="button ${style}" disabled>${text}</button>` : btn(text, action, style);
    const selected = methods => methods.find(row => row.id === form?.methodId);
    const selectedVersion = methods => selected(methods)?.versions.find(row => row.number === form.version);
    const filePayload = () => ({fileName:form.fileName,content:form.encoded,encoding:form.encoding,sheet:form.sheet});
    const showError = () => error ? note(esc(error),'red') : '<p role="status" class="hint">正在读取…</p>';
    const invalidateCheck = () => { form.checked = null; form.skipInvalid = false; };

    async function work(operation) {
      if (busy) return;
      busy = true;
      redraw();
      try { await operation(); } catch (e) { notify(e.message, true); }
      finally { busy = false; redraw(); }
    }

    function schedule(fetchAgain, on) {
      clearTimeout(poll);
      if (on) poll = setTimeout(fetchAgain, 2200);
    }

    async function listLoad(background = false) {
      const token = ++stamp;
      if (!background) { list = null; error = ''; redraw(); }
      try {
        const response = await request(query(listFilter));
        if (token !== stamp) return;
        list = response;
        error = '';
        schedule(() => listLoad(true), list.items.some(row => active(row.status)));
      } catch (e) {
        if (token !== stamp) return;
        error = e.message;
        if (background) schedule(() => listLoad(true), true);
      }
      redraw();
    }

    async function taskLoad(background = false) {
      const token = ++stamp, id = ui.batchTaskId;
      if (!background) { task = null; error = ''; redraw(); }
      try {
        const response = await request(`/${encodeURIComponent(id)}${query(itemFilter)}`);
        if (token !== stamp) return;
        task = response;
        error = '';
        schedule(() => taskLoad(true), active(task.status));
      } catch (e) {
        if (token !== stamp) return;
        error = e.message;
        if (background) schedule(() => taskLoad(true), true);
      }
      redraw();
    }

    async function route() {
      const next = ui.page + (ui.page === 'taskDetail' ? ':' + ui.batchTaskId : '');
      if (key === next) return;
      key = next;
      clearTimeout(poll);
      ++stamp;
      if (ui.page === 'batch') return listLoad();
      if (ui.page === 'taskDetail') {
        itemFilter = {status:'',discipline:'',page:1};
        return taskLoad();
      }
      if (ui.page === 'overview') {
        const token = stamp;
        try {
          const response = await request();
          if (stamp === token) { recent = response.items.slice(0,3); redraw(); }
        } catch (_) { /* The batch page displays connection and permission errors. */ }
      }
    }

    function newTask(methods, methodId, version) {
      const method = methods.find(row => row.id === String(methodId)) || methods[0];
      const chosen = method?.versions.find(row => row.number === version)?.number || method?.defaultVersion || '';
      form = {name:'',methodId:method?.id || '',version:chosen,task:null,basis:'',encoding:'UTF-8',sheet:'',
        fileName:'',encoded:'',preview:null,mapping:{},key1:'',key2:'',checked:null,skipInvalid:false,submitKey:''};
      step = 1;
      return goto('batchWizard');
    }

    async function draft(methods) {
      const method = selected(methods), version = selectedVersion(methods);
      const name = form.name.trim();
      if (!name || !version) throw new Error('请填写任务名称并选择已保存的方法版本');
      const basis = JSON.stringify([name,method.id,version.id]);
      if (basis !== form.basis) {
        form.task = await request('', 'POST', {name,methodId:Number(method.id),methodVersionId:version.id});
        form.basis = basis;
        invalidateCheck();
      }
      step = 2;
    }

    async function inspect(resetMapping) {
      if (!form.encoded) return;
      form.preview = null;
      invalidateCheck();
      const preview = await request(`/${form.task.id}/imports/inspect`, 'POST', filePayload());
      form.preview = preview;
      form.sheet = preview.sheet || '';
      if (resetMapping || !Object.values(form.mapping).some(value => preview.columns.includes(value))) {
        form.mapping = Object.fromEntries(mappings.map(([key,, aliases]) => [key, preview.columns.find(column =>
          aliases.includes(column.toLowerCase())) || '']));
        form.key1 = form.mapping.externalId;
        form.key2 = '';
      } else {
        for (const key of Object.keys(form.mapping)) if (!preview.columns.includes(form.mapping[key])) form.mapping[key] = '';
        if (!preview.columns.includes(form.key1)) form.key1 = '';
        if (!preview.columns.includes(form.key2)) form.key2 = '';
      }
    }

    function setFile(file) {
      if (!file || !form?.task) return;
      return work(async () => {
        if (file.size > 8 * 1024 * 1024) throw new Error('请选择 8 MiB 以内的文件');
        form.fileName = file.name;
        form.encoded = '';
        form.sheet = '';
        form.preview = null;
        invalidateCheck();
        const dataUrl = await new Promise((resolve,reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result);
          reader.onerror = () => reject(new Error('无法读取上传文件'));
          reader.readAsDataURL(file);
        });
        form.encoded = String(dataUrl).split(',')[1];
        await inspect(true);
      });
    }

    function bind(key, value, commit) {
      if (key === 'listSearch') { listFilter.search = value; return; }
      if (key === 'listStatus') { listFilter.status = value; listFilter.page = 1; return listLoad(); }
      if (key === 'itemStatus' || key === 'itemDiscipline') {
        itemFilter[key === 'itemStatus' ? 'status' : 'discipline'] = value;
        itemFilter.page = 1;
        return taskLoad();
      }
      if (!form) return;
      if (key === 'methodId') { form.methodId = value; return; }
      if (key === 'sheet' || key === 'encoding') {
        if (commit && value !== form[key]) {
          form[key] = value;
          return work(() => inspect(true));
        }
        return;
      }
      if (key.startsWith('map:')) {
        form.mapping[key.slice(4)] = value;
        invalidateCheck();
      } else {
        form[key] = value;
        if (['key1','key2'].includes(key)) invalidateCheck();
      }
      if (commit && key !== 'name') redraw();
    }

    function grid(headers, rows) {
      return `<div class="debug-table"><table class="step-detail-table"><thead><tr>${headers.map(cell=>`<th>${esc(cell)}</th>`).join('')}</tr></thead>
        <tbody>${rows.map(row=>`<tr>${row.map(cell=>`<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
    }

    function pagination(data, action) {
      if (!data) return '';
      return `<div class="card-actions mt12">${button('上一页',action+':'+(data.page-1),'small',data.page<=1)}
        <span class="hint">第 ${data.page} 页 · 共 ${data.total} 条</span>
        ${button('下一页',action+':'+(data.page+1),'small',data.page*data.pageSize>=data.total)}</div>`;
    }

    const versionText = row => `${esc(row.methodName)} v${esc(row.methodVersionNo)} · ${esc(row.knowledgeName)} v${esc(row.knowledgeVersionNo)}`;
    const counts = row => {
      const p = row.progress;
      return `提交 ${p.total} · 成功 ${p.succeeded} · 失败 ${p.failed} · 执行中 ${p.running} · 待处理 ${p.pending}`;
    };

    function listPage() {
      const title = pageHead('','批量识别','上传论文文件，提交后台任务并查看逐条识别结果。',btn('新建批量任务','batch:new','primary'));
      const controls = `<div class="toolbar"><div class="toolbar-left">
        ${field('任务名称',listFilter.search,'batch:listSearch',{placeholder:'搜索任务'})}
        ${field('状态',listFilter.status,'batch:listStatus',{type:'select',options:[['','全部'],...Object.entries(taskLabels).filter(([k])=>k!=='draft')]})}
        ${btn('查询','batch:search','small')}</div></div>`;
      const body = error ? showError() + btn('重新加载','batch:search','small') : !list ? showError()
        : !list.items.length ? empty('暂无批量任务','新建任务后，可在此查看后台执行进度。')
        : grid(['任务','方法与知识版本','提交时间','进度','状态',''],list.items.map(row=>[
          esc(row.name),versionText(row),esc(day(row.submittedAt)),esc(counts(row)),status(row.status),
          btn('查看','batch:open:'+row.id,'small')]))+pagination(list,'batch:listPage');
      return title + controls + `<section class="panel detail-block">${body}</section>`;
    }

    function wizard(methods) {
      if (!form) return pageHead('','新建批量任务','选择已保存的方法版本开始。',btn('开始','batch:new','primary'));
      const method = selected(methods), version = selectedVersion(methods);
      let content = '';
      if (step === 1) {
        content = field('任务名称',form.name,'batch:name',{disabled:busy}) +
          field('识别方法',form.methodId,'batch:methodId',{type:'select',disabled:busy,options:methods.map(row=>[row.id,row.name])}) +
          field('方法版本',form.version,'batch:version',{type:'select',disabled:busy,options:method?.versions.map(row=>row.number)||[]}) +
          (version ? note(`知识库 ${esc(version.knowledgeName)} ${esc(version.knowledgeVersion)}`) : note('暂无可运行的方法版本。'));
      } else if (step === 2) {
        const csv = !form.fileName || form.fileName.toLowerCase().endsWith('.csv');
        const upload = `<div class="section-head"><strong>上传和检查</strong>${btn('下载 CSV 模板','batch:template','small')}</div>
          <input type="file" accept=".csv,.xlsx" data-batch-file aria-label="上传论文文件" ${busy?'disabled':''}>
          <p class="hint">${esc(form.fileName || '支持 CSV、XLSX，最多 5000 篇、8 MiB。')}</p>
          ${csv ? field('CSV 编码',form.encoding,'batch:encoding',{type:'select',disabled:busy,options:['UTF-8','GB18030']}) : ''}
          ${form.preview?.sheets.length ? field('工作表',form.sheet,'batch:sheet',{type:'select',disabled:busy,options:form.preview.sheets}) : ''}`;
        const options = [['','未选择'],...(form.preview?.columns || [])];
        const checked = form.checked;
        content = upload;
        if (form.preview?.columns.length) {
          content += `<h3 class="section-title mt18">列映射</h3><div class="field-grid three">${mappings.map(([key,label]) =>
            field(label,form.mapping[key],'batch:map:'+key,{type:'select',disabled:busy,options})).join('')}</div>
            <div class="field-grid">${field('去重业务键',form.key1,'batch:key1',{type:'select',disabled:busy,options})}
            ${field('组合业务键（可选）',form.key2,'batch:key2',{type:'select',disabled:busy,options})}</div>
            <p class="hint">可组合学科代码与原始编号，或留空按源行区分。共 ${form.preview.total} 行。</p>
            ${grid(['行号',...form.preview.columns],form.preview.rows.map(row=>[esc(row.sourceRowNo),...form.preview.columns.map((_,i)=>esc(row.cells[i]||''))]))}
            ${button('检查数据','batch:check','secondary mt12',busy)}`;
        } else if (form.preview) {
          content += note('该工作表为空，请选择有论文数据的工作表。');
        }
        if (checked) {
          const summary = checked.summary;
          content += `<div class="section-head mt18"><strong>检查结果</strong>${badge('有效 '+summary.valid,'green')} ${badge('异常 '+summary.invalid,'amber')}</div>
            ${grid(['源行号','外部编号','标题','问题'],checked.rows.map(row=>[esc(row.sourceRowNo),esc(row.input.externalId),
              esc(row.input.title),esc(row.issues.join('；') || '通过')]))}
            ${summary.invalid ? `<label class="checkbox-line mt12"><input type="checkbox" data-bind="batch:skipInvalid" ${form.skipInvalid?'checked':''}>
              跳过 ${summary.invalid} 条异常行</label>${btn('下载异常清单','batch:invalidDraft','small')}` : ''}`;
        }
      } else {
        const summary = form.checked.summary;
        content = `<h3 class="section-title">${esc(form.name)}</h3><p>${versionText(form.task)}</p>
          <p>文件 ${esc(form.fileName)} ${form.sheet ? ' · 工作表 '+esc(form.sheet) : ''}</p>
          <p>上传 ${summary.total} 条，异常 ${summary.invalid} 条，提交 ${summary.valid} 条。</p>
          ${note('提交后由后台执行，可以离开本页。')}`;
      }
      return pageHead('','新建批量任务','选择方法、检查数据并确认提交。',btn('返回任务列表','nav:batch','small')) +
        `<div class="toolbar"><strong>${step} / 3 · ${['选择方法','上传与检查','确认提交'][step-1]}</strong></div>
        <section class="panel detail-block">${content}</section><div class="wizard-footer">
        ${button(step===1?'取消':'上一步',step===1?'nav:batch':'batch:back','small',busy)}
        ${button(step===3?'提交后台执行':'下一步',step===3?'batch:submit':'batch:next','primary',busy)}</div>`;
    }

    function taskPage() {
      if (!task) return pageHead('','批量任务详情','',btn('返回任务列表','nav:batch','small')) + showError() + btn('重新加载','batch:reload','small');
      const p = task.progress;
      const actions = btn('返回任务列表','nav:batch','small') + btn('复制任务','batch:duplicate','small') +
        button('重试失败项','batch:retry','secondary',busy || active(task.status) || !p.failed);
      const summary = `<div class="snapshot-bar"><div>${status(task.status)}　${counts(task)}
        <div class="hint">${esc(task.fileName)} ${task.sheetName ? ' · '+esc(task.sheetName):''} · 上传 ${task.importSummary?.total ?? '—'}
          · 跳过 ${task.importSummary?.invalid ?? '—'} · ${esc(task.submittedBy)} · ${esc(day(task.submittedAt))}</div></div>
        ${btn('查看方法配置','batch:method','small ghost')}</div>`;
      const tools = `<div class="toolbar"><div class="toolbar-left">
        ${field('条目状态',itemFilter.status,'batch:itemStatus',{type:'select',options:[['','全部'],...Object.entries(rowLabels).filter(([k])=>!['reused','skipped'].includes(k))]})}
        ${field('入选学科',itemFilter.discipline,'batch:itemDiscipline',{type:'select',options:[['','全部'],...task.disciplineOptions.map(row=>[row.code,row.name])]})}
        </div><div class="toolbar-right">${button('导出成功结果','batch:export:results','small',!p.succeeded)}
          ${button('导出失败项','batch:export:failed','small',!p.failed)}
          ${button('下载异常行','batch:export:invalid','small',!task.importSummary?.invalid)}</div></div>`;
      const rows = task.results.items;
      return pageHead('',esc(task.name),versionText(task),actions) + summary + tools + (error?showError():'') +
        `<section class="panel detail-block">${rows.length ? grid(['源行','外部编号','标题','主学科','其他学科','交叉度','状态',''],rows.map(row=>[
          esc(row.sourceRowNo),esc(row.externalRecordId || '—'),esc(row.title || '—'),esc(row.mainDiscipline || '—'),
          esc(row.disciplines.slice(1).map(x=>x.name).join('、') || '—'),esc(row.metricValue ?? (row.adoptedResultId ? '未提供' : '—')),
          status(row.status,rowLabels),btn('查看过程','batch:item:'+row.id,'small')])) : empty('暂无匹配论文','可调整状态或学科筛选。')}
          ${pagination(task.results,'batch:itemPage')}</section>`;
    }

    async function loadItem(id, run) {
      const token = ++itemStamp, taskId = ui.batchTaskId;
      item = null; itemError = ''; trace = null; tracePhase = ''; withCalls = false;
      openModal('batchItem',{id});
      try {
        const response = await request(`/${taskId}/items/${id}${run ? query({run}) : ''}`);
        if (token !== itemStamp) return;
        item = response;
      } catch (e) { if (token === itemStamp) itemError = e.message; }
      if (ui.modal === 'batchItem') renderModal();
    }

    async function loadTrace(phase, calls) {
      const token = ++itemStamp;
      tracePhase = phase; withCalls = calls; trace = null; traceError = '';
      renderModal();
      try {
        const response = await request(`/${ui.batchTaskId}/items/${item.item.id}/runs/${item.run.id}/stages/${phase}${query({calls})}`);
        if (token === itemStamp) trace = response;
      } catch (e) { if (token === itemStamp) traceError = e.message; }
      if (ui.modal === 'batchItem') renderModal();
    }

    function itemView() {
      if (itemError) return note(esc(itemError),'red');
      if (!item) return '<p class="hint">正在读取论文运行…</p>';
      const {run, attempts, item:row} = item;
      return `<h3 class="section-title">${esc(row.title || '未填写标题')}</h3>
        <p class="hint">源行 ${row.sourceRowNo} · ${esc(row.externalRecordId || '无外部编号')} · ${status(row.status,rowLabels)}</p>
        ${attempts.length ? `<div class="card-actions">${attempts.map(attempt=>btn(
          `第 ${attempt.batchAttemptNo} 次 · ${esc(rowLabels[attempt.status]||attempt.status)}`,
          'batch:attempt:'+attempt.id,run?.id===attempt.id?'small secondary':'small')).join('')}</div>` : ''}
        ${!run ? '<p class="hint">等待后台领取。</p>' : `<p>${versionText(run)} · 运行 #${run.id}</p>
        ${run.errorMessage ? note(esc(run.errorMessage),'red') : ''}
        <details><summary>原始论文输入</summary><pre class="code-raw debug-code">${esc(JSON.stringify(run.input,null,2))}</pre></details>
        ${run.result ? grid(['名次','学科','等级','理由'],run.result.disciplines.map(result=>[
          esc(result.finalRank),esc(result.name),esc(result.finalLevel),esc(result.reason)])) : ''}
        <div class="card-actions mt12">${phases.map(([code,label])=>btn(
          `${label} · ${esc(rowLabels[run.stages.find(s=>s.stageCode===code)?.status] || '待处理')}`,
          'batch:phase:'+code,code===tracePhase?'small secondary':'small')).join('')}</div>
        ${tracePhase ? `<div class="mt12">${['router','recall','judge'].includes(tracePhase) ?
          btn(withCalls?'收起调用详情':'调用详情','batch:callDetails','small') : ''}
          ${traceError ? note(esc(traceError),'red') : trace ? renderEvidence(tracePhase,trace,withCalls) :
            '<p class="hint">正在读取阶段快照…</p>'}</div>` : ''}
        ${btn('复制输入到单篇调试','batch:copyInput','small secondary mt12')}`}`;
    }

    async function exportFile(taskId, kind, importId) {
      const response = await fetch(`${root}/${taskId}/export${query({kind,...(importId ? {importId} : {})})}`,{credentials:'same-origin'});
      if (!response.ok) throw new Error((await response.json()).message || '导出失败');
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement('a');
      anchor.href = url; anchor.download = `batch-${taskId}-${kind}.csv`;
      document.body.append(anchor); anchor.click(); anchor.remove();
      setTimeout(()=>URL.revokeObjectURL(url),1000);
    }

    function handle(action, value, methods) {
      switch (action) {
        case 'new': return newTask(methods);
        case 'open': return goto('taskDetail',{batchTaskId:value});
        case 'search': listFilter.page=1; return listLoad();
        case 'listPage': listFilter.page=Number(value); return listLoad();
        case 'itemPage': itemFilter.page=Number(value); return taskLoad();
        case 'reload': return taskLoad();
        case 'method': return openMethod(String(task.methodId),'v'+task.methodVersionNo);
        case 'duplicate': {
          newTask(methods,task.methodId,'v'+task.methodVersionNo);
          form.name=task.name+'（副本）'; return redraw();
        }
        case 'methodChoice': form.version=selected(methods)?.defaultVersion || ''; return redraw();
        case 'template': return download('batch-template.csv','编号,标题,摘要,关键词,期刊,年份,DOI\r\n');
        case 'back': if (!busy) { step--; redraw(); } break;
        case 'next': return work(async()=>{
          if (step===1) return draft(methods);
          if (!form.checked?.summary.valid) throw new Error('请先检查文件并确认存在有效行');
          if (form.checked.summary.invalid && !form.skipInvalid) throw new Error('请明确选择跳过异常行，或上传修正后的文件');
          step=3;
        });
        case 'check': return work(async()=>{
          form.checked=await request(`/${form.task.id}/imports`,'POST',{
            ...filePayload(),mapping:form.mapping,businessKeyColumns:[form.key1,form.key2].filter(Boolean)
          });
          form.skipInvalid=false;
          form.submitKey=crypto.randomUUID();
        });
        case 'submit': return work(async()=>{
          const queued=await request(`/${form.task.id}/submit`,'POST',{
            importId:form.checked.importId,skipInvalid:form.skipInvalid,submitKey:form.submitKey
          });
          form=null;
          notify('已提交到后台执行');
          goto('taskDetail',{batchTaskId:String(queued.id)});
        });
        case 'retry': return work(async()=>{
          await request(`/${task.id}/retry`,'POST');
          notify('失败项已重新排队');
          return taskLoad(true);
        });
        case 'invalidDraft': return work(()=>exportFile(form.task.id,'invalid',form.checked.importId));
        case 'export': return work(()=>exportFile(task.id,value));
        case 'item': return loadItem(Number(value));
        case 'attempt': return loadItem(item.item.id,Number(value));
        case 'phase': return loadTrace(value,false);
        case 'callDetails': return loadTrace(tracePhase,!withCalls);
        case 'copyInput': if (item?.run) { closeModal(); copyToDebug(item.run); } break;
      }
    }

    function overview() {
      return recent.length ? recent.map(row=>`<div class="row-item"><div class="grow"><strong>${esc(row.name)}</strong>
        <div class="row-sub">${esc(counts(row))}</div></div>${status(row.status)} ${btn('查看','batch:open:'+row.id,'small')}</div>`).join('')
        : '<p class="hint detail-block">暂无可显示的批量任务。</p>';
    }

    function search(value) { listFilter = {search:value,status:'',page:1}; if (ui.page === 'batch') listLoad(); }
    return {route,newTask,handle,bind,setFile,listPage,wizard,taskPage,itemView,overview,search};
  }
});
})();

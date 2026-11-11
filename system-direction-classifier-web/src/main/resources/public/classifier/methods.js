/* Recognition method configuration backed by /api/methods. */
(() => {
  'use strict';
  const api = '/api/methods';
  const names = {parse:'输入解析', router:'簇路由', recall:'学科召回', judge:'独立精判', post:'后处理', result:'学科结果'};
  const labels = {
    recall_max_abstract_chars:'召回摘要截取长度', min_clusters:'最少选簇数', max_clusters:'最多选簇数',
    min_candidates:'候选下限', max_candidates:'候选上限', modelRevisionId:'模型修订',
    high_relevance_cap:'高度相关学科上限', top_k_default:'默认输出学科数', temperature:'温度',
    max_tokens:'最大输出 Token 数', timeout_seconds:'超时（秒）', llm_attempt_limit:'单次调用尝试上限',
    enable_thinking:'思考模式', candidate_attempt_limit:'候选精判尝试上限',
    candidate_backoff_seconds_per_attempt:'候选精判重试间隔（秒）', system:'系统提示词', user:'识别提示词',
    knowledgeVersionId:'知识库版本'
  };
  const basic = {
    parse:['recall_max_abstract_chars'], router:['max_clusters','min_clusters'], recall:['max_candidates','min_candidates'],
    judge:[], post:['high_relevance_cap'], result:['top_k_default']
  };
  const stageNotes = {
    parse:'结构化字段优先。召回使用标题、关键词、期刊和摘要，精判使用标题、DOI、期刊、年份和摘要。',
    router:'从所选知识库版本的学科簇中选出可能相关的簇。',
    recall:'动态召回，候选不足时先从选中簇补足，再按知识版本的摘要顺序兜底。',
    judge:'每次判定一个候选学科，依据该学科的精判画像文本。',
    post:'按相关等级、量化召回分和召回名次排序，超出高度相关上限的候选降为中度相关。',
    result:'输出有效学科及完整候选链，主学科取有效结果首位。'
  };
  const callFields = ['temperature','max_tokens','timeout_seconds','llm_attempt_limit','enable_thinking'];
  const emptyFilters = {search:'', taxonomy:'', scope:'', archived:false, page:1};
  const state = {filters:{...emptyFilters}, edits:{}, deps:{}, token:0};
  let ui, root, navigate, notify, refreshOptions, writeRoute, h;

  async function request(path = '', method = 'GET', body) {
    const headers = {Accept:'application/json'};
    if (method !== 'GET') {
      const response = await fetch('/api/auth/csrf', {credentials:'same-origin'});
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.data?.csrfToken) throw new Error(result?.message || '请求校验失效，请刷新页面');
      headers['X-CSRF-Token'] = result.data.csrfToken;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(api + path, {method, headers, credentials:'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body)});
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.success) {
      const error = new Error(result?.message || '识别方法服务暂不可用');
      error.status = response.status;
      error.issues = result?.data?.issues;
      throw error;
    }
    return result.data;
  }

  const label = value => value == null ? '—' : 'v' + value;
  const date = value => value ? new Date(/(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value.replace(' ','T')+'Z')
    .toLocaleString('zh-CN',{hour12:false}) : '—';
  const saved = method => (method.versions || []).filter(item => item.state === 'saved');
  const defaultId = method => method.defaultVersionId || method.latestVersionId;
  const button = (text, action, style = 'small', disabled = false) =>
    `<button type="button" class="button ${style}" data-method-action="${h.esc(action)}" ${disabled ? 'disabled' : ''}>${h.esc(text)}</button>`;
  const option = (id, text, selected) =>
    `<option value="${h.esc(id)}" ${String(id) === String(selected ?? '') ? 'selected' : ''}>${h.esc(text)}</option>`;
  const options = (rows, selected) => rows.map(([id,text]) => option(id,text,selected)).join('');
  const scope = row => ({builtin:'内置', private:'个人', team:row.teamName || '团队共享'})[row.visibility];
  const knowledgeLabel = row => row.knowledgeName ? `${row.knowledgeName} ${label(row.knowledgeVersionNo ?? row.versionNo)}` : '未关联';
  const methodPath = () => '/' + encodeURIComponent(state.method.id);
  const dirty = () => state.version?.state === 'draft' &&
    (Object.keys(state.edits).length > 0 || Object.hasOwn(state,'knowledgeChange'));

  function input(label, value, attrs, params = {}) {
    const escaped = h.esc(value);
    const disabled = params.readonly || state.busy ? 'disabled' : '';
    const control = params.options ? `<select class="form-control" ${attrs} ${disabled}>${options(params.options,value)}</select>`
      : params.rows ? `<textarea class="form-control textarea" ${attrs} rows="${params.rows}" ${disabled}>${escaped}</textarea>`
        : `<input class="form-control" ${attrs} value="${escaped}" ${disabled} ${params.number ? 'type="number" step="any"' : ''}>`;
    return `<label class="field"><span class="label">${h.esc(label)}</span>${control}</label>`;
  }

  function listView() {
    const data = state.catalog;
    const f = state.filters;
    const head = h.pageHead('','识别方法','管理识别流程、阶段配置与已保存版本。',
      data?.canCreate ? button('新建方法','new','primary') : '');
    if (!data) return head + status();
    const rows = data.items.map(row => `<tr><td><strong>${h.esc(row.name)}</strong><div class="table-desc">${h.esc(row.description)}</div></td>
      <td>${h.esc(row.ownerLogin)}<div class="tiny muted">${h.esc(scope(row))}</div></td>
      <td>${h.esc(row.taxonomyName)}</td><td>${label(row.latestNumber)}</td><td>${label(row.defaultNumber)}</td>
      <td>${h.esc(knowledgeLabel(row))}</td><td>${h.esc(date(row.updatedAt))}</td>
      <td>${row.archivedAt ? h.badge('已归档') : row.currentDraftId ? h.badge('有草稿','amber') : h.badge(row.latestVersionId ? '已保存' : '未保存')}</td>
      <td>${button('查看 / 配置','open:'+row.id)}${row.canCopy && defaultId(row) ? button('创建副本','listCopy:'+row.id,'small ghost') : ''}</td></tr>`).join('');
    const pagination = Math.max(1,Math.ceil(data.total/data.pageSize));
    return head + `<form class="toolbar" data-method-form="filters"><div class="toolbar-left">
      <label class="searchbox">${h.ic('search',16)}<input name="search" aria-label="搜索方法" placeholder="搜索方法名称或说明" value="${h.esc(f.search)}"></label>
      <select class="filter-select" name="scope" aria-label="方法范围">${options([['','全部方法'],['mine','我的方法'],['team','团队共享'],['builtin','内置方法']],f.scope)}</select>
      <select class="filter-select" name="taxonomy" aria-label="学科体系">${options([['','全部学科体系'],...data.taxonomies.map(x=>[x.code,x.name])],f.taxonomy)}</select>
      <label class="smalltext"><input type="checkbox" name="archived" ${f.archived ? 'checked' : ''}> 已归档</label>
      <button class="button" type="submit">筛选</button></div></form>
      ${state.error || state.loading ? status() : `<section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr>
      <th>方法名称</th><th>所有者</th><th>学科体系</th><th>最新版本</th><th>默认版本</th><th>关联知识库</th><th>更新时间</th><th>状态</th><th>操作</th>
      </tr></thead><tbody>${rows}</tbody></table></div>${rows ? '' : h.empty('没有匹配的方法','尝试调整搜索条件。')}</section>
      <div class="toolbar mt12"><span class="smalltext muted">共 ${data.total} 项 · 第 ${data.page} / ${pagination} 页</span><div class="card-actions">
      ${button('上一页','page:'+(data.page-1),'small',data.page<=1)}${button('下一页','page:'+(data.page+1),'small',data.page>=pagination)}</div></div>`}`;
  }

  function status() {
    if (state.error) return h.empty('读取方法失败',h.esc(state.error),button('重试','reload'));
    return '<div class="panel panel-pad" role="status">正在读取识别方法…</div>';
  }

  function detailView() {
    const m = state.method, v = state.version;
    const back = `<div class="mini-nav">${button('识别方法','back','small ghost')}${h.ic('chevron',13)}${h.esc(m?.name || '方法详情')}</div>`;
    if (!m || state.loading || state.error) return back + status();
    const draft = v?.state === 'draft';
    const actions = draft ? button('配置校验','validate') + button('保存草稿','save') +
      (m.canPublish ? button('保存新版本','publish','primary') : '') :
      (v && m.canEdit ? button(m.currentDraftId ? '打开已有草稿' : '基于此创建草稿','draft:'+v.id) : '');
    const manage = (m.canManage ? button('修改方法信息','edit') + button(m.archivedAt ? '恢复方法' : '归档方法','archive') : '') +
      (m.canCopy && (v?.state === 'saved' || defaultId(m)) ? button('创建副本','copy:'+(v?.state === 'saved' ? v.id : defaultId(m))) : '');
    const knowledgeLink = v?.knowledgeBaseId && !Object.hasOwn(state,'knowledgeChange')
      ? button(knowledgeLabel(v),'knowledge','small ghost') : h.esc(knowledgeLabel(v || m));
    return back + h.pageHead('',h.esc(m.name),h.esc(m.description),actions + manage) +
      `<div class="meta-strip"><span>所有者 <strong>${h.esc(m.ownerLogin)}</strong></span><span>学科体系 <strong>${h.esc(m.taxonomyName)}</strong></span>
      <span>知识库 ${knowledgeLink}</span>${m.archivedAt ? h.badge('已归档') : ''}
      <span data-method-draft-status>${h.esc(draft ? dirty() ? '草稿有未保存修改' : '草稿' : v ? '已保存 '+label(v.versionNo) : '')}</span></div>
      <div class="tabbar"><button class="tab ${ui.methodTab === 'flow' ? 'active' : ''}" data-method-action="tab:flow">流程配置</button>
      <button class="tab ${ui.methodTab === 'versions' ? 'active' : ''}" data-method-action="tab:versions">版本记录 ${saved(m).length}</button></div>
      ${ui.methodTab === 'versions' ? versionList(m) : v ? configuration(m,v,draft) : h.empty('没有可查看的版本','方法编辑者保存版本后即可在此查看。')}`;
  }

  function versionList(method) {
    const rows = saved(method);
    if (!rows.length) return h.empty('尚未保存版本','完成草稿配置后，可以保存第一个版本。');
    return `<section class="panel"><div class="version-row heading"><div>版本</div><div>保存说明</div><div>时间 / 保存人</div><div>知识库版本</div><div>操作</div></div>
      ${rows.map(v => `<div class="version-row"><div>${h.badge(label(v.versionNo),v.id === method.defaultVersionId ? 'green' : 'neutral')}
      ${v.id === method.defaultVersionId ? '<div class="tiny muted mt8">默认使用</div>' : ''}</div><div>${h.esc(v.changeNote)}</div>
      <div>${h.esc(date(v.savedAt))}<div class="tiny muted">${h.esc(v.savedByLogin)}</div></div><div>${h.esc(knowledgeLabel(v))}</div>
      <div class="card-actions">${button('查看','select:'+v.id)}${method.canEdit && !method.currentDraftId ? button('基于此创建草稿','draft:'+v.id) : ''}
      ${method.canCopy ? button('复制','copy:'+v.id) : ''}${method.canSetDefault && v.id !== method.defaultVersionId ? button('设为默认','default:'+v.id) : ''}</div></div>`).join('')}</section>`;
  }

  function knowledgeChoices(rows, selected, currentName) {
    const result = [['','暂不关联'],...rows.map(row=>[row.id,knowledgeLabel(row)+(row.isDefault ? '（默认）' : '')])];
    if (selected != null && !rows.some(row=>row.id === selected)) result.push([selected,(currentName || '版本 #'+selected)+'（当前不可选）']);
    return result;
  }

  function configuration(method, version, draft) {
    const current = version.stages.find(stage => stage.stageCode === ui.methodStage) || version.stages[0];
    const selector = method.versions.map(v=>[v.id,v.state === 'draft' ? '草稿' : '已保存 '+label(v.versionNo)]);
    const knowledge = Object.hasOwn(state,'knowledgeChange') ? state.knowledgeChange : version.knowledgeVersionId;
    const knowledgeOptions = knowledgeChoices(state.deps.knowledgeVersions || [],knowledge,knowledgeLabel(version));
    const nodes = version.stages.map(stage => `<button class="flow-node ${stage === current ? 'active' : ''}" data-method-action="stage:${h.esc(stage.stageCode)}">
      <span class="node-name">${h.esc(names[stage.stageCode] || stage.stageCode)}</span></button>`);
    const references = version.basedOnNumber ? `基于 ${label(version.basedOnNumber)}` : method.copiedFromVersionId ? `复制自版本 #${method.copiedFromVersionId}` : '';
    return `<div class="toolbar"><div class="toolbar-left"><label class="smalltext">配置对象 <select class="filter-select" data-method-select>
      ${options(selector,version.id)}</select></label>${h.esc(references)}</div></div>
      <div class="flow-layout"><div class="flow-left"><section class="panel flow-board"><div class="flow-caption"><strong>识别流程</strong></div>
      <div class="lane-nodes">${nodes.join(`<span class="flow-arrow">${h.ic('arrow',15)}</span>`)}</div></section>
      ${draft ? `<section class="panel panel-pad mt18">${input('知识库版本',knowledge,'data-method-knowledge',{options:knowledgeOptions,readonly:!!state.deps.error})}
      ${state.deps.error ? `<p class="hint text-danger">${h.esc(state.deps.error)}</p>` : ''}</section>` : ''}</div>
      <section class="panel inspector">${current ? inspector(current,!draft) : h.empty('没有阶段配置','请检查此方法版本。')}</section></div>`;
  }

  function inspector(stage, readonly) {
    const code = stage.stageCode, edit = state.edits[code] || {};
    const read = (section,key) => Object.hasOwn(edit[section] || {},key) ? edit[section][key] : stage[section]?.[key];
    const field = (section,key,params) => input(labels[key] || key, read(section,key),
      `data-method-stage="${code}" data-section="${section}" data-key="${key}"`,{readonly,...params});
    const selected = Object.hasOwn(edit,'modelRevisionId') ? edit.modelRevisionId : stage.modelRevisionId;
    const modelOptions = [['','请选择模型修订'],...(state.deps.models || []).map(row=>[row.revisionId,`${row.name} · ${row.modelId} · 修订 ${row.revisionNo}`])];
    if (selected != null && !modelOptions.some(([id])=>id===selected)) modelOptions.push([selected,stage.modelLabel || `修订 #${selected}`]);
    const model = stage.prompts ? input('模型连接',selected,`data-method-stage="${code}" data-key="modelRevisionId"`,{readonly,options:modelOptions}) : '';
    const advanced = stage.prompts ? [
      field('prompts','system',{rows:4}), field('prompts','user',{rows:12}),
      `<div class="hint">模板变量 ${h.esc((stage.prompts.variables || []).map(name=>'{'+name+'}').join('、'))}。JSON 字面大括号使用双大括号。</div>`,
      ...[...callFields,...(code==='judge' ? ['candidate_attempt_limit','candidate_backoff_seconds_per_attempt'] : [])].map(key=>
        field('callOptions',key,key === 'enable_thinking' ? {options:[[true,'开启'],[false,'关闭']]} : {number:true}))
    ].join('') : '';
    return `<div class="inspector-head"><strong class="inspector-title">${h.esc(names[code] || code)}</strong> ${readonly ? h.badge('只读') : ''}
      <p class="hint">${h.esc(stageNotes[code])}</p></div><div class="inspector-body">${model}
      ${(basic[code] || []).map(key=>field('config',key,{number:true})).join('')}
      ${advanced ? `${button(state.advanced?'收起高级配置':'高级配置','advanced','small ghost')}${state.advanced ? `<div class="mt12">${advanced}</div>` : ''}` : ''}
      <details class="mt12"><summary>阶段输出字段</summary><div class="hint">${h.esc((stage.outputContract?.fields || []).join('、'))}</div>
      ${stage.outputContract?.call_fields ? `<div class="hint">模型调用 ${h.esc(stage.outputContract.call_fields.join('、'))}</div>` : ''}</details></div>`;
  }

  function dialogView() {
    const d = state.dialog;
    if (!d) return '';
    const field = (caption,key,settings={}) => input(caption,d.values?.[key],`data-method-dialog-field="${key}"`,settings);
    let body = '', submit = '';
    if (d.type === 'new') {
      body = field('方法名称','name') + field('说明','description',{rows:3}) + field('学科体系','taxonomyId',
        {options:state.catalog.taxonomies.map(row=>[row.id,row.name])}) + field('知识库版本','knowledgeVersionId',
        {options:knowledgeChoices(d.knowledge || [],d.values.knowledgeVersionId),readonly:d.loading});
      submit = '创建草稿';
    } else if (d.type === 'copy') {
      body = field('副本名称','name'); submit = '创建副本';
    } else if (d.type === 'edit') {
      const choices = state.method.visibility === 'builtin' ? [['builtin','内置']] : [['private','个人'],['team','团队共享']];
      body = field('方法名称','name') + field('说明','description',{rows:3}) + field('共享范围','visibility',{options:choices}) +
        (d.values.visibility === 'team' ? field('共享团队','teamId',{options:[['','请选择团队'],...(state.method.teams || []).map(row=>[row.id,row.name])]}) : '');
      submit = '保存信息';
    } else if (d.type === 'publish') {
      body = field('修改说明','changeNote',{rows:3}); submit = '保存新版本';
    } else if (d.type === 'archive') {
      body = h.note(state.method.archivedAt ? '恢复后将重新出现在方法列表。' : '归档后可从已归档列表查看和恢复。');
      submit = state.method.archivedAt ? '确认恢复' : '确认归档';
    } else if (d.type === 'validation') {
      body = d.issues.length ? `<div class="validation-list">${d.issues.map(item=>
        `<div class="validation-item text-danger">${h.esc([names[item.stage],labels[item.field],item.message].filter(Boolean).join(' · '))}</div>`).join('')}</div>`
        : h.note('知识版本、模型修订、提示词和阶段参数校验通过。');
    } else if (d.type === 'leave') {
      body = h.note('当前草稿有未保存修改。');
    }
    const error = d.error ? `<p class="hint text-danger" role="alert">${h.esc(d.error)}</p>` : '';
    return `<div class="modal-overlay"><section class="modal" role="dialog" aria-modal="true" aria-label="${h.esc(d.title)}">
      <form data-method-form="dialog"><div class="modal-header"><strong>${h.esc(d.title)}</strong>${button('关闭','close','small ghost',state.busy)}</div>
      <div class="modal-body">${body}${error}</div><div class="modal-footer">
      ${d.type === 'leave' ? button('放弃修改','discard','secondary',state.busy)+button('保存草稿并继续','saveLeave','primary',state.busy) : ''}
      ${submit ? `<button class="button primary" type="submit" ${state.busy || d.loading ? 'disabled' : ''}>${submit}</button>` : ''}</div></form></section></div>`;
  }

  function draw() {
    if (root) root.innerHTML = (ui.page === 'methods' ? listView() : detailView()) + dialogView();
  }

  function resetEdits() {
    state.edits = {};
    delete state.knowledgeChange;
  }

  async function load() {
    const token = ++state.token;
    state.loading = true;
    state.error = '';
    draw();
    try {
      if (ui.page === 'methods') {
        const query = new URLSearchParams(state.filters);
        const data = await request('?'+query);
        if (token !== state.token) return;
        if (data.page > 1 && !data.items.length) {
          state.filters.page = Math.max(1,Math.ceil(data.total/data.pageSize));
          return load();
        }
        state.catalog = data;
        if (state.newPending) {
          state.newPending = false;
          if (data.canCreate) openNew();
        }
      } else {
        const method = await request('/'+encodeURIComponent(ui.methodId));
        if (token !== state.token) return;
        state.method = method;
        const active = method.versions.find(v=>String(v.id)===ui.methodVersion)
          || method.versions.find(v=>v.id===(ui.methodView === 'version' ? defaultId(method) : method.currentDraftId || defaultId(method)));
        if (active) {
          ui.methodVersion = String(active.id);
          ui.methodView = active.state === 'draft' ? 'draft' : 'version';
          state.key = viewKey();
          writeRoute(true);
          const version = await request(methodPath()+'/versions/'+active.id);
          if (token !== state.token) return;
          state.version = version;
          if (active.state === 'draft') {
            const deps = await request(`/dependencies?taxonomyId=${method.taxonomyId}&methodId=${method.id}`).catch(error=>({error:error.message}));
            if (token !== state.token) return;
            state.deps = deps;
          }
        }
      }
    } catch (error) {
      if (token === state.token) state.error = error.message;
    } finally {
      if (token === state.token) { state.loading = false; draw(); }
    }
  }

  const viewKey = () => ui.page === 'methods' ? 'catalog' : [ui.methodId,ui.methodView,ui.methodVersion].join('/');
  const selectVersion = (versionId, draft = false) => navigate('methodDetail',{
    methodId:String(state.method.id), methodVersion:String(versionId), methodView:draft?'draft':'version', methodTab:'flow'
  });
  const open = id => navigate('methodDetail',{methodId:String(id),methodVersion:'',methodView:'',methodTab:'flow',methodStage:'router'});
  function openDialog(type,title,extra={}) {
    state.dialog = {type,title,values:{},...extra};
    draw();
  }

  function openNew() {
    if (!state.catalog?.canCreate) return;
    if (!state.catalog.taxonomies.length) { notify('当前没有可选学科体系',true); return; }
    openDialog('new','新建识别方法',{values:{taxonomyId:state.catalog.taxonomies[0].id}});
    loadNewKnowledge();
  }

  async function loadNewKnowledge() {
    const dialog = state.dialog, taxonomy = dialog.values.taxonomyId;
    dialog.loading = true;
    dialog.error = '';
    dialog.knowledge = [];
    delete dialog.values.knowledgeVersionId;
    draw();
    try {
      const data = await request('/dependencies?taxonomyId='+taxonomy);
      if (state.dialog !== dialog || dialog.values.taxonomyId !== taxonomy) return;
      dialog.knowledge = data.knowledgeVersions;
      dialog.values.knowledgeVersionId = dialog.knowledge.find(item=>item.isDefault)?.id || dialog.knowledge[0]?.id || null;
    } catch (error) {
      if (state.dialog === dialog && dialog.values.taxonomyId === taxonomy) dialog.error = error.message;
    } finally {
      if (state.dialog === dialog && dialog.values.taxonomyId === taxonomy) { dialog.loading = false; draw(); }
    }
  }

  function payload() {
    const input = {draftId:state.version.id,revision:state.version.revision,stageChanges:Object.values(state.edits)};
    if (Object.hasOwn(state,'knowledgeChange')) input.knowledgeVersionId = state.knowledgeChange;
    return input;
  }

  async function saveDraft() {
    const result = await request(methodPath()+'/draft','PUT',payload());
    resetEdits();
    state.method = result.method;
    state.version = result.version;
    notify('草稿已保存');
  }

  async function run(operation) {
    if (state.busy) return;
    state.busy = true;
    draw();
    try { await operation(); }
    catch (error) {
      if (error.issues) openDialog('validation','方法配置校验',{issues:error.issues});
      else if (state.dialog) state.dialog.error = error.message;
      else notify(error.message,true);
    } finally {
      state.busy = false;
      draw();
    }
  }

  async function submitDialog() {
    const d = state.dialog;
    if (!d) return;
    if (d.type === 'new' || d.type === 'copy') {
      const path = d.type === 'new' ? '' : `/${d.method}/versions/${d.version}/copy`;
      const row = await request(path,'POST',d.values);
      state.dialog = null;
      refreshOptions();
      notify(d.type === 'new' ? '方法草稿已创建' : '方法副本已创建');
      open(row.id);
    } else if (d.type === 'edit') {
      state.method = await request(methodPath(),'PUT',d.values);
      state.dialog = null;
      refreshOptions();
      notify('方法信息已保存');
    } else if (d.type === 'archive') {
      const archive = !state.method.archivedAt;
      await request(methodPath()+'/archive','PUT',{archived:archive});
      state.dialog = null;
      refreshOptions();
      notify(archive ? '方法已归档' : '方法已恢复');
      navigate('methods');
    } else if (d.type === 'publish') {
      const result = await request(methodPath()+'/draft/publish','POST',{...payload(),...d.values});
      resetEdits();
      state.dialog = null;
      refreshOptions();
      notify('已保存 '+label(result.version.versionNo));
      selectVersion(result.version.id);
    }
  }

  function perform(action) {
    if (state.busy) return;
    const [command,value] = action.split(':');
    const m = state.method;
    switch (command) {
      case 'open': return open(value);
      case 'back': return navigate('methods');
      case 'reload': return load();
      case 'page': state.filters.page = Number(value); return load();
      case 'new': return openNew();
      case 'close': state.dialog = null; return draw();
      case 'stage': ui.methodStage = value; state.advanced = state.preferAdvanced; writeRoute(); return draw();
      case 'tab': ui.methodTab = value; writeRoute(); return draw();
      case 'advanced': state.advanced = !state.advanced; return draw();
      case 'select': return selectVersion(value,m.versions.some(v=>String(v.id)===value && v.state==='draft'));
      case 'knowledge': return navigate('kbDetail',{kbId:String(state.version.knowledgeBaseId),
        kbVersion:String(state.version.knowledgeVersionId),kbVersionMode:'saved',kbTab:'profiles',kbSelected:''});
      case 'edit': return openDialog('edit','修改方法信息',{values:{name:m.name,description:m.description,visibility:m.visibility,teamId:m.teamId}});
      case 'listCopy': {
        const row = state.catalog.items.find(item=>String(item.id)===value);
        return openDialog('copy','创建方法副本',{method:row.id,version:defaultId(row),values:{name:row.name+' · 副本'}});
      }
      case 'copy': {
        const proceed = () => openDialog('copy','创建方法副本',{method:m.id,version:value,values:{name:m.name+' · 副本'}});
        return dirty() ? openDialog('leave','处理未保存的草稿',{proceed}) : proceed();
      }
      case 'archive': {
        const proceed = () => openDialog('archive',m.archivedAt ? '恢复方法' : '归档方法');
        return dirty() ? openDialog('leave','处理未保存的草稿',{proceed}) : proceed();
      }
      case 'publish': return openDialog('publish','保存新版本',{values:{changeNote:state.changeNote || ''}});
      case 'save': return run(saveDraft);
      case 'validate': return run(async()=>{
        const report = await request(methodPath()+'/draft/validate','POST',payload());
        openDialog('validation','方法配置校验',{issues:report.issues});
      });
      case 'default': return run(async()=>{
        state.method = await request(methodPath()+'/versions/'+value+'/default','PUT');
        refreshOptions();
        notify('默认使用版本已更新');
      });
      case 'draft':
        if (m.currentDraftId) return selectVersion(m.currentDraftId,true);
        return run(async()=>{
          const row = await request(methodPath()+'/draft','POST',{versionId:Number(value)});
          notify('草稿已创建');
          selectVersion(row.currentDraftId,true);
        });
      case 'discard': {
        const proceed = state.dialog.proceed;
        resetEdits();
        state.dialog = null;
        return proceed();
      }
      case 'saveLeave': return run(async()=>{
        const proceed = state.dialog.proceed;
        await saveDraft();
        state.dialog = null;
        proceed();
      });
    }
  }

  const numeric = value => value === '' ? null : Number(value);
  function editControl(control) {
    const {methodStage, section, key, methodDialogField} = control.dataset;
    if (methodDialogField) {
      if (!state.dialog) return;
      const values = state.dialog.values;
      const next = methodDialogField.endsWith('Id') ? numeric(control.value) : control.value;
      if (values[methodDialogField] === next) return;
      values[methodDialogField] = next;
      if (methodDialogField === 'changeNote') state.changeNote = next;
      if (methodDialogField === 'taxonomyId') loadNewKnowledge();
      if (methodDialogField === 'visibility') draw();
      return;
    }
    if (control.hasAttribute('data-method-knowledge')) state.knowledgeChange = numeric(control.value);
    if (methodStage && state.version?.state === 'draft') {
      const edits = state.edits[methodStage] ||= {stageCode:methodStage};
      if (key === 'modelRevisionId') edits.modelRevisionId = numeric(control.value);
      else (edits[section] ||= {})[key] = control.type === 'number' ? numeric(control.value)
        : key === 'enable_thinking' ? control.value === 'true' : control.value;
    }
    if (dirty()) {
      const indicator = root.querySelector('[data-method-draft-status]');
      if (indicator) indicator.textContent = '草稿有未保存修改';
    }
  }

  window.ClassifierMethods = Object.freeze({
    options: () => request('/options'),
    search(query) { state.filters.search = query; state.filters.page = 1; state.key = null; },
    newOnMount() { state.newPending = true; state.key = null; },
    preferAdvanced(value) { state.preferAdvanced = value; },
    dismiss() {
      if (!root || !state.dialog || state.busy) return false;
      state.dialog = null; draw(); return true;
    },
    dirty,
    guard(page,params,proceed) {
      if (!dirty() || ui.page !== 'methodDetail') return false;
      if (page === 'methodDetail' && (!params.methodId || params.methodId === ui.methodId)
          && (!Object.hasOwn(params,'methodVersion') || params.methodVersion === ui.methodVersion)) return false;
      openDialog('leave','离开方法草稿',{proceed});
      return true;
    },
    unmount() { root = null; state.key = null; state.token++; state.dialog = null; delete state.changeNote; resetEdits(); },
    mount(element,route,callbacks,helpers) {
      root = element; ui = route; h = helpers;
      ({navigate,notify,refreshOptions,writeRoute} = callbacks);
      root.onclick = event => {
        const action = event.target.closest('[data-method-action]');
        if (action) perform(action.dataset.methodAction);
      };
      root.oninput = event => editControl(event.target);
      root.onchange = event => {
        if (event.target.hasAttribute('data-method-select')) perform('select:'+event.target.value);
        else editControl(event.target);
      };
      root.onsubmit = event => {
        event.preventDefault();
        if (event.target.dataset.methodForm === 'filters') {
          const form = new FormData(event.target);
          state.filters = {search:form.get('search'),taxonomy:form.get('taxonomy'),scope:form.get('scope'),archived:form.has('archived'),page:1};
          load();
        } else run(submitDialog);
      };
      const key = viewKey();
      if (state.key === key) return draw();
      state.key = key;
      state.method = null;
      state.version = null;
      state.deps = {};
      state.dialog = null;
      state.advanced = state.preferAdvanced;
      delete state.changeNote;
      resetEdits();
      load();
    }
  });
})();

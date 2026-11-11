(() => {
  'use strict';

  const api = '/api/model-connections';
  const types = {chat: '对话模型', embedding: '向量模型'};
  const statuses = {enabled: '已启用', disabled: '已停用'};
  const providers = {generic: '通用 OpenAI 兼容服务', dashscope: '阿里云百炼', vllm: 'vLLM'};
  const escape = value => String(value ?? '').replace(/[&<>"']/g, char =>
    ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'})[char]);
  const option = (value, label, selected) =>
    `<option value="${escape(value)}" ${String(value) === String(selected) ? 'selected' : ''}>${escape(label)}</option>`;
  const options = (items, selected) => Object.entries(items).map(([value, label]) => option(value, label, selected)).join('');
  const button = (label, action, id, style = 'small', disabled = false) =>
    `<button type="button" class="button ${style}" data-model-action="${action}" ${id == null ? '' : `data-id="${id}"`} ${disabled ? 'disabled' : ''}>${escape(label)}</button>`;
  const badge = (label, color = 'neutral') => `<span class="badge ${color}">${escape(label)}</span>`;
  const date = value => value
    ? new Date(/(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value.replace(' ', 'T') + 'Z').toLocaleString('zh-CN', {hour12:false})
    : '—';
  const json = value => {
    if (!value) return {};
    if (typeof value !== 'string') return value;
    try { return JSON.parse(value); } catch (_) { return {}; }
  };
  const additional = row => json(row.routingOptionsJson).additional_endpoints || [];
  const addressOptions = row => [row.baseUrl, ...additional(row).map(item => item.base_url)];
  const keyStatus = row => row.authType === 'none' ? '无需凭据' : row.credentialConfigured ? '已配置' : '未配置';
  const testStatus = status => status === 'success' ? badge('调用成功', 'green')
    : status === 'failed' ? badge('调用失败', 'amber') : badge('待测试');
  const scope = row => row.visibility === 'team' ? row.teamName || '团队共享' : row.visibility === 'builtin' ? '内置' : '个人';
  const input = (label, name, value, attributes = '') =>
    `<label class="field"><span class="label">${label}</span><input class="form-control" name="${name}" value="${escape(value)}" ${attributes}></label>`;
  const select = (label, name, choices) =>
    `<label class="field"><span class="label">${label}</span><select class="form-control" name="${name}">${choices}</select></label>`;

  async function request(path = '', method = 'GET', body) {
    const headers = {Accept:'application/json'};
    if (method !== 'GET') {
      const csrf = await fetch('/api/auth/csrf', {credentials:'same-origin'}).then(response => response.json());
      if (!csrf.success || !csrf.data?.csrfToken) throw new Error('请求校验失效，请刷新页面');
      headers['X-CSRF-Token'] = csrf.data.csrfToken;
      headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(api + path, {method, headers, credentials:'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body)});
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.success) {
      const error = new Error(result?.message || '模型连接服务暂不可用');
      error.status = response.status;
      throw error;
    }
    return result.data;
  }

  const filters = {search: '', type: '', status: '', page: 1};
  let root, overlay, notify, changed, catalog, loadError, dialog, serial = 0;

  function draw() {
    if (!root?.isConnected) return;
    const toolbar = `<form class="toolbar" data-model-search><div class="toolbar-left">
      <label class="searchbox"><input aria-label="搜索模型连接" name="search" value="${escape(filters.search)}" placeholder="搜索连接或模型名称"></label>
      <select class="filter-select" aria-label="模型类型" name="type">${option('', '全部类型', filters.type)}${options(types, filters.type)}</select>
      <select class="filter-select" aria-label="启停状态" name="status">${option('', '全部状态', filters.status)}${options(statuses, filters.status)}</select>
      <button class="button small" type="submit">查询</button></div>
      ${catalog?.canCreate ? button('新增连接', 'add', null, 'primary') : ''}</form>`;
    if (loadError || !catalog) {
      root.innerHTML = toolbar + `<section class="panel"><div class="empty" role="status">
        ${escape(loadError?.message || '正在加载模型连接…')}
        ${loadError ? button('重试', 'refresh') : ''}
        ${loadError?.status === 401 ? '<a class="button small" href="/identity/">前往登录</a>' : ''}
        </div></section>`;
      return;
    }
    const rows = catalog.items.map(row => `<tr>
      <td><div class="table-main">${escape(row.name)}</div><div class="table-desc">${escape(row.description)}</div></td>
      <td><span style="overflow-wrap:anywhere">${escape(row.baseUrl)}</span>
        ${additional(row).length ? `<div class="hint">另有 ${additional(row).length} 个附加地址</div>` : ''}</td>
      <td>${escape(row.modelId)}<div class="hint">${escape(types[row.modelType])} · 修订 ${row.revisionNo}</div></td>
      <td>${escape(row.ownerName || row.ownerLogin)}<div class="hint">${escape(scope(row))} · ${keyStatus(row)}</div></td>
      <td>${badge(statuses[row.status], row.status === 'enabled' ? 'green' : 'neutral')}</td>
      <td>${testStatus(row.lastCallStatus)}<div class="hint">${escape(date(row.lastTestedAt))}
        ${row.lastHttpStatus ? ` · HTTP ${row.lastHttpStatus}` : ''}</div></td>
      <td><div class="method-actions">${button('查看', 'view', row.id)}${row.canManage ? button('编辑', 'edit', row.id) : ''}
        ${row.canTest ? button('测试', 'test', row.id) : ''}${catalog.canCreate ? button('复制', 'copy', row.id, 'small ghost') : ''}
        ${row.canManage ? button(row.status === 'enabled' ? '停用' : '启用', 'status', row.id, 'small ghost') : ''}</div></td></tr>`).join('');
    const lastPage = Math.max(1, Math.ceil(catalog.total / catalog.pageSize));
    root.innerHTML = toolbar + `<section class="panel">
      ${rows ? `<div class="table-wrap"><table class="data-table"><thead><tr><th>连接名称</th><th>服务地址</th>
        <th>模型</th><th>所有者和范围</th><th>状态</th><th>最近测试（主地址）</th><th>操作</th></tr></thead>
        <tbody>${rows}</tbody></table></div>` : '<div class="empty">没有符合条件的模型连接</div>'}
      <div class="toolbar"><span class="hint">共 ${catalog.total} 条 · 第 ${filters.page} / ${lastPage} 页</span>
        <div class="toolbar-right">${button('上一页', 'previous', null, 'small', filters.page <= 1)}
        ${button('下一页', 'next', null, 'small', filters.page >= lastPage)}</div></div></section>`;
  }

  async function refresh() {
    const stamp = ++serial;
    loadError = null;
    try {
      const data = await request('?' + new URLSearchParams(filters));
      if (stamp !== serial) return;
      if (!data.items.length && data.page > 1) {
        filters.page = Math.max(1, Math.ceil(data.total / data.pageSize));
        return refresh();
      }
      catalog = data;
    } catch (error) {
      if (stamp !== serial) return;
      catalog = null;
      loadError = error;
    }
    draw();
  }

  function view(row) {
    const revisions = row.revisions.map(revision => `<div class="row-item">
      <div class="grow"><strong>修订 ${revision.revisionNo} · ${escape(revision.modelId)}</strong>
      <div class="row-sub" style="overflow-wrap:anywhere">${escape(revision.baseUrl)} · ${escape(types[revision.modelType])} ·
        ${escape(date(revision.createdAt))}</div></div>${revision.revisionId === row.revisionId ? badge('当前', 'green') : ''}</div>`).join('');
    const history = row.tests.map(test => `<div class="row-item"><div class="grow">
      <strong>${escape(date(test.testedAt))}</strong>
      <div class="row-sub" style="overflow-wrap:anywhere">${escape(test.testedBaseUrl)} · 修订
        ${escape(row.revisions.find(revision => revision.revisionId === test.connectionRevisionId)?.revisionNo || '—')}</div>
      <div class="row-sub">${escape(test.errorMessage || test.responsePreview || '')}</div></div>
      ${testStatus(test.callStatus)}<span class="smalltext">${test.httpStatus ? `HTTP ${test.httpStatus} · ` : ''}${test.durationMs} ms</span></div>`).join('');
    return `<div class="smalltext" style="overflow-wrap:anywhere">
      <p>${escape(row.description)}</p><p>${escape(row.baseUrl)}</p>
      <p>${escape(providers[row.providerCode] || 'OpenAI 兼容')} · ${escape(types[row.modelType])} · ${escape(row.modelId)}</p>
      <p>${escape(scope(row))} · ${keyStatus(row)} · ${escape(statuses[row.status])}</p>
      ${additional(row).map(item => `<p>附加地址 ${escape(item.base_url)}（权重 ${item.weight}）</p>`).join('')}</div>
      <h3>配置修订</h3>${revisions}<h3>最近测试记录</h3>${history || '<p class="hint">暂无测试记录</p>'}`;
  }

  function editor(row) {
    const auth = row.authType || 'bearer';
    const team = row.teamId || '';
    const thinking = json(row.adapterOptionsJson).enable_thinking;
    const addresses = additional(row).map(item => `${item.base_url} | ${item.weight}`).join('\n');
    return `${input('连接名称', 'name', row.name, 'required maxlength="120"')}
      <label class="field"><span class="label">说明</span><textarea class="form-control" name="description" rows="2" maxlength="1000">${escape(row.description)}</textarea></label>
      <div class="field-grid">${select('协议', 'protocolCode', option('openai_compatible', 'OpenAI 兼容', 'openai_compatible'))}
      ${select('服务适配', 'providerCode', options(providers, row.providerCode || 'generic'))}</div>
      ${input('Base URL', 'baseUrl', row.baseUrl, 'required maxlength="2048" placeholder="https://服务地址/v1"')}
      <div class="field-grid">${input('模型 ID', 'modelId', row.modelId, 'required maxlength="255"')}
      ${select('模型类型', 'modelType', options(types, row.modelType || 'chat'))}</div>
      ${select('认证方式', 'authType', options({bearer:'API Key（Bearer）', none:'无需凭据'}, auth))}
      <div data-model-key>${input('API Key', 'apiKey', '', 'type="password" autocomplete="new-password" maxlength="4096"')}
        <p class="hint">${row.id ? `${keyStatus(row)}。留空保留同一地址范围的现有凭据，换地址后可填写新 Key。` : '可以先保存连接，再配置凭据。'}</p></div>
      ${select('可使用范围', 'visibility', options({private:'个人', team:'团队共享'}, row.visibility || 'private'))}
      <div data-model-team>${select('团队', 'teamId', option('', '请选择团队', team) +
        (catalog?.teams || []).map(item => option(item.id, item.name, team)).join(''))}</div>
      <details ${addresses || thinking != null ? 'open' : ''}><summary>高级配置</summary>
        <div data-model-thinking>${select('思考模式', 'enableThinking',
          options({'':'服务默认', true:'启用', false:'关闭'}, thinking ?? ''))}</div>
        <label class="field"><span class="label">附加服务地址</span><textarea class="form-control" name="addresses"
          rows="3" placeholder="每行一个 Base URL | 权重">${escape(addresses)}</textarea></label>
        <p class="hint">最多四个附加地址，地址组使用同一凭据。修改模型或地址会保存新的配置修订。</p>
      </details>`;
  }

  function syncEditor(form) {
    const fields = form.elements;
    form.querySelector('[data-model-key]').hidden = fields.authType.value !== 'bearer';
    fields.apiKey.disabled = fields.authType.value !== 'bearer';
    form.querySelector('[data-model-team]').hidden = fields.visibility.value !== 'team';
    fields.teamId.required = fields.visibility.value === 'team';
    form.querySelector('[data-model-thinking]').hidden = fields.modelType.value !== 'chat' || fields.providerCode.value === 'generic';
  }

  function testSummary(test) {
    if (!test) return '';
    const usage = json(test.usageJson);
    const reachable = test.reachable == null ? '未确认' : test.reachable ? '可达' : '不可达';
    return `<div class="inline-note ${test.callStatus === 'success' ? '' : 'amber'}" role="status"><div>
      <strong>${test.callStatus === 'success' ? '调用成功' : '调用失败'}</strong>
      <div>地址${reachable} · ${test.httpStatus ? `HTTP ${test.httpStatus} · ` : ''}${test.durationMs} ms · ${escape(date(test.testedAt))}</div>
      ${test.returnedModelId ? `<div>返回模型 ${escape(test.returnedModelId)}</div>` : ''}
      ${usage.embedding_dimension ? `<div>向量维度 ${usage.embedding_dimension}</div>` : ''}
      ${usage.total_tokens != null ? `<div>总 Token ${usage.total_tokens}</div>` : ''}
      ${test.providerRequestId ? `<div>请求 ID ${escape(test.providerRequestId)}</div>` : ''}
      <div>${escape(test.errorMessage || test.responsePreview || '')}</div></div></div>`;
  }

  function close() {
    if (dialog?.busy) return;
    dialog = null;
    if (overlay) overlay.innerHTML = '';
  }

  function open(mode, row = {}) {
    dialog = {mode, row, busy:false};
    const title = ({add:'新增模型连接', edit:'编辑模型连接', view:'查看模型连接', test:'连接测试', copy:'复制连接'})[mode];
    let body;
    if (mode === 'view') body = view(row);
    else if (mode === 'test') {
      body = `<p>${escape(row.name)} · ${escape(row.modelId)} · 修订 ${row.revisionNo}</p>
        ${select('测试地址', 'baseUrl', addressOptions(row).map(address => option(address, address, row.baseUrl)).join(''))}
        <div data-model-test-result></div>`;
    } else if (mode === 'copy') {
      body = input('副本名称', 'name', `${row.name.slice(0, 116)}（副本）`, 'required maxlength="120"') +
        '<p class="hint">副本属于当前用户，保存后可单独配置凭据。</p>';
    } else body = editor(row);
    const submit = mode === 'test' ? '开始测试' : mode === 'copy' ? '创建副本' : '保存连接';
    overlay.innerHTML = `<div class="drawer-overlay"><form class="drawer" data-model-form role="dialog" aria-modal="true" aria-label="${title}">
      <div class="modal-header"><strong>${title}</strong>${button('关闭', 'close', null, 'small ghost')}</div>
      <div class="modal-body">${body}<p class="hint" data-model-feedback role="status"></p></div>
      <div class="modal-footer">${button(mode === 'view' ? '关闭' : '取消', 'close')}
        ${mode === 'view' ? '' : `<button class="button primary" type="submit">${submit}</button>`}</div></form></div>`;
    const form = overlay.querySelector('form');
    if (mode === 'add' || mode === 'edit') {
      syncEditor(form);
      form.onchange = () => syncEditor(form);
    }
    form.onsubmit = submitDialog;
    (form.querySelector('input, select') || form.querySelector('button')).focus();
  }

  function bodyFrom(form, row) {
    const data = Object.fromEntries(new FormData(form));
    const addresses = data.addresses.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
    const additionalEndpoints = addresses.map(line => {
      const parts = line.split('|').map(part => part.trim());
      if (parts.length > 2 || (parts.length === 2 && !/^\d+$/.test(parts[1]))) {
        throw new Error('附加地址格式为 Base URL | 权重');
      }
      return {baseUrl:parts[0], weight:parts.length === 1 ? 1 : Number(parts[1])};
    });
    return {name:data.name, description:data.description, protocolCode:data.protocolCode, providerCode:data.providerCode,
      baseUrl:data.baseUrl, modelId:data.modelId, modelType:data.modelType, authType:data.authType,
      visibility:data.visibility, teamId:data.teamId ? Number(data.teamId) : null, additionalEndpoints,
      enableThinking:data.modelType === 'chat' && data.providerCode !== 'generic' && data.enableThinking
        ? data.enableThinking === 'true' : null,
      ...(data.apiKey ? {apiKey:data.apiKey} : {}), ...(row.id ? {editToken:row.editToken} : {})};
  }

  async function submitDialog(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const state = dialog;
    if (!state || state.busy || state.mode === 'view') return;
    const {mode, row} = state;
    const feedback = form.querySelector('[data-model-feedback]');
    const submit = form.querySelector('[type="submit"]');
    state.busy = submit.disabled = true;
    feedback.textContent = mode === 'test' ? '正在请求模型服务…' : '正在保存…';
    try {
      if (mode === 'test') {
        const result = await request(`/${row.id}/tests`, 'POST', {revisionId:row.revisionId, baseUrl:form.elements.baseUrl.value});
        form.querySelector('[data-model-test-result]').innerHTML = testSummary(result);
        feedback.textContent = '';
      } else {
        if (mode === 'copy') await request(`/${row.id}/copy`, 'POST', {name:form.elements.name.value});
        else await request(row.id ? `/${row.id}` : '', row.id ? 'PUT' : 'POST', bodyFrom(form, row));
        state.busy = false;
        if (dialog === state) close();
        notify(mode === 'copy' ? '已创建连接副本' : '模型连接已保存');
      }
      changed();
      await refresh();
    } catch (error) {
      feedback.textContent = error.message;
    } finally {
      state.busy = submit.disabled = false;
    }
  }

  async function click(event) {
    const target = event.target.closest('[data-model-action]');
    if (!target || target.disabled) return;
    const action = target.dataset.modelAction;
    if (action === 'close') return close();
    if (action === 'add') return open('add');
    if (action === 'refresh') return refresh();
    if (action === 'previous' || action === 'next') {
      filters.page += action === 'next' ? 1 : -1;
      return refresh();
    }
    const container = root;
    target.disabled = true;
    try {
      const row = await request('/' + target.dataset.id);
      if (!container.isConnected || root !== container) return;
      if (action === 'status') {
        const status = row.status === 'enabled' ? 'disabled' : 'enabled';
        await request(`/${row.id}/status`, 'PUT', {status, editToken:row.editToken});
        notify(status === 'disabled' ? '模型连接已停用' : '模型连接已启用');
        changed();
        await refresh();
      } else open(action, row);
    } catch (error) {
      notify(error.message, true);
    } finally {
      target.disabled = false;
    }
  }

  window.ClassifierModelConnections = Object.freeze({
    options: () => request('/options'),
    mount(container, showToast, onChange) {
      const entering = !root || !root.isConnected;
      root = container;
      notify = showToast;
      changed = onChange;
      overlay = document.querySelector('#model-connection-overlay');
      root.onclick = overlay.onclick = click;
      root.onsubmit = event => {
        if (!event.target.matches('[data-model-search]')) return;
        event.preventDefault();
        const form = new FormData(event.target);
        Object.assign(filters, Object.fromEntries(form), {page:1});
        refresh();
      };
      draw();
      if (entering) refresh();
    },
    unmount() {
      dialog = null;
      root = null;
      if (overlay) overlay.innerHTML = '';
    },
    dismiss() {
      if (!dialog) return false;
      close();
      return true;
    }
  });
})();

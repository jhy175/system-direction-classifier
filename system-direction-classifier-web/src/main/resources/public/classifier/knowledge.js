(() => {
  'use strict';

  const api = '/api/knowledge';
  const tabs = {
    profiles: '学科画像', summaries: '召回摘要', clusters: '学科簇',
    papers: '种子论文', similarities: '相似度资源', versions: '版本记录'
  };
  const tasks = {
    core_tasks: '核心研究任务', peripheral_tasks: '边缘研究任务',
    cross_tasks: '交叉任务', differentiation: '学科区分规则'
  };
  const counts = {profiles: '画像', summaries: '摘要', clusters: '簇', papers: '种子论文'};
  const escape = value => String(value ?? '').replace(/[&<>"']/g, char =>
    ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'})[char]);
  const option = (value, label, selected = false) =>
    `<option value="${escape(value)}" ${selected ? 'selected' : ''}>${escape(label)}</option>`;
  const btn = (label, command, style = 'small') =>
    `<button type="button" class="button ${style}" data-kb-action="${escape(command)}">${escape(label)}</button>`;
  const badge = (label, color = 'neutral') => `<span class="badge ${color}">${escape(label)}</span>`;
  const note = message => `<p class="hint">${escape(message)}</p>`;
  const versionLabel = value => value == null ? '—' : 'v' + value;
  const pretty = value => JSON.stringify(value, null, 2);
  const owner = row => row.ownerLogin || row.ownerName || '系统';
  const errorText = error => error.message || '知识库请求失败';
  const formatDate = value => {
    if (!value) return '—';
    const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? value : value.replace(' ','T')+'Z';
    return new Date(utc).toLocaleString('zh-CN', {hour12:false});
  };
  const scopeLabel = row => ({builtin:'内置', private:'个人', team:row.teamName || '团队共享'})[row.visibility];
  const fields = (label, name, value = '', opts = {}) => {
    const control = opts.rows
      ? `<textarea class="form-control" name="${name}" rows="${opts.rows}" ${opts.required ? 'required' : ''}>${escape(value)}</textarea>`
      : `<input class="form-control" type="${opts.type || 'text'}" name="${name}" value="${escape(value)}" ${opts.required ? 'required' : ''} ${opts.readonly ? 'readonly' : ''}>`;
    return `<label class="field"><span class="label">${escape(label)}</span>${control}</label>`;
  };
  const select = (label, name, options) =>
    `<label class="field"><span class="label">${escape(label)}</span><select class="form-control" name="${name}" required>${options}</select></label>`;
  const words = value => Array.isArray(value) ? value.join('\n') : '';
  const lines = value => String(value || '').split(/\r?\n|[、；;]/).map(line => line.trim()).filter(Boolean);
  const pairs = values => Object.entries(values).map(([key, value]) => `${counts[key] || key} ${value}`).join(' · ');

  async function request(url, method = 'GET', body) {
    const headers = {Accept:'application/json'};
    if (method !== 'GET') {
      headers['X-CSRF-Token'] = (await request('/api/auth/csrf')).csrfToken;
      if (body !== undefined) headers['Content-Type'] = 'application/json';
    }
    const response = await fetch(url, {method, headers, credentials:'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body)});
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.success) {
      const error = new Error(result?.message || '知识库服务暂不可用');
      error.status = response.status;
      throw error;
    }
    return result.data;
  }

  function download(name, value) {
    const url = URL.createObjectURL(new Blob([pretty(value)], {type:'application/json;charset=utf-8'}));
    const link = document.createElement('a');
    link.href = url;
    link.download = name;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  async function readUpload(file, limit) {
    if (!file || file.size === 0) throw new Error('请选择文件');
    if (file.size > limit * 1024 * 1024) throw new Error(`文件不能超过 ${limit} MB`);
    const bytes = new Uint8Array(await file.arrayBuffer());
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 16384) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 16384));
    }
    return {fileName:file.name, content:btoa(binary)};
  }

  const filterState = {search:'', taxonomy:'', scope:'', archived:false, page:1};
  let pendingImportFor = null;

  class KnowledgePage {
    constructor(root, route, navigate, toast) {
      this.root = root;
      this.route = route;
      this.navigate = navigate;
      this.toast = toast;
      this.root.addEventListener('click', event => {
        const button = event.target.closest('[data-kb-action]');
        if (button) this.run(() => this.action(button.dataset.kbAction), button);
      });
      this.root.addEventListener('change', event => {
        if (event.target.id === 'kb-version-picker') {
          this.open(this.base.id, event.target.value, this.tab);
        }
      });
    }

    async run(fn, button) {
      if (button) button.disabled = true;
      try { await fn(); }
      catch (error) { if (this.root.isConnected) this.toast(errorText(error), true); }
      finally { if (button) button.disabled = false; }
    }

    async load() {
      this.root.innerHTML = '<p class="hint" role="status">正在加载知识库…</p>';
      try {
        if (this.route.page === 'knowledge') {
          this.catalog = await request(api + '?' + new URLSearchParams(filterState));
          if (this.root.isConnected) this.renderList();
          return;
        }
        this.base = await request(api + '/' + encodeURIComponent(this.route.kbId));
        const versions = this.base.versions;
        const requested = versions.find(version => String(version.id) === String(this.route.kbVersion));
        const selected = requested || versions.find(version => version.id === this.base.defaultVersionId) ||
          versions.find(version => version.id === this.base.latestVersionId) || versions[0];
        this.tab = tabs[this.route.kbTab] ? this.route.kbTab : 'profiles';
        if (selected) this.snapshot = await request(this.versionUrl(selected.id));
        if (!this.root.isConnected) return;
        this.renderDetail();
        if (this.draft && pendingImportFor === this.base.id) {
          pendingImportFor = null;
          this.import();
        }
        if (this.tab === 'profiles' && this.snapshot?.resources.profiles.length) {
          const profile = this.snapshot.resources.profiles.find(item => item.code === this.route.kbSelected) ||
            this.snapshot.resources.profiles[0];
          this.selectProfile(profile.id);
        }
      } catch (error) {
        if (!this.root.isConnected) return;
        this.root.innerHTML = `<h1 class="page-title">学科知识库</h1><div class="inline-note red" role="alert">${escape(errorText(error))}</div>
          <div class="action-row mt12">${error.status === 401 ? '<a class="button primary" href="/identity/">前往登录</a>' : btn('重试','reload')}
          ${this.route.page === 'kbDetail' ? btn('返回知识库','list') : ''}</div>`;
      }
    }

    heading(title, description, actions = '') {
      return `<div class="page-head"><div><h1 class="page-title">${escape(title)}</h1><p class="page-desc">${escape(description)}</p></div>
        <div class="page-actions">${actions}</div></div>`;
    }

    open(id, version = '', tab = 'profiles', selected = '') {
      this.navigate('kbDetail', {kbId:String(id), kbVersion:String(version), kbTab:tab, kbSelected:selected, kbVersionMode:''});
    }

    versionUrl(version = this.snapshot.version.id) { return `${api}/${this.base.id}/versions/${version}`; }
    draftUrl(suffix = '') { return `${api}/${this.base.id}/draft${suffix}`; }
    get draft() { return this.snapshot?.version.state === 'draft' && this.base.canEdit; }
    draftInput(extra = {}) {
      return {draftId:this.snapshot.version.id, revision:this.snapshot.version.revision, ...extra};
    }

    renderList() {
      const {items, taxonomies, total, page, pageSize, canCreate} = this.catalog;
      const scope = [['','全部范围'],['mine','我的知识库'],['team','团队共享'],['builtin','内置']];
      this.root.innerHTML = this.heading('学科知识库', '管理学科画像、召回摘要、学科簇和离线资源。',
        canCreate ? btn('导入知识库', 'create:import') + btn('创建知识库', 'create', 'primary') : '') + `
        <form class="toolbar kb-filter" data-kb-filter>
          <input name="search" class="form-control" aria-label="搜索知识库" placeholder="搜索名称或说明" value="${escape(filterState.search)}">
          <select class="filter-select" name="taxonomy" aria-label="学科体系">${option('','全部学科体系')}
          ${taxonomies.map(row => option(row.code,row.name,row.code === filterState.taxonomy)).join('')}</select>
          <select class="filter-select" name="scope" aria-label="共享范围">${scope.map(([key,label]) => option(key,label,key === filterState.scope)).join('')}</select>
          <label class="checkbox-line"><input type="checkbox" name="archived" ${filterState.archived?'checked':''}> 已归档</label>
          <button class="button small" type="submit">搜索</button>
        </form>
        <section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th>知识库</th><th>学科体系</th>
          <th>学科数量</th><th>所有者</th><th>共享范围</th><th>最新 / 默认</th><th>最近更新</th><th>操作</th></tr></thead><tbody>
          ${items.map(row => `<tr><td><strong>${escape(row.name)}</strong><div class="table-desc">${escape(row.description)}</div>
            ${row.currentDraftId ? badge('有草稿','amber') : ''}</td><td>${escape(row.taxonomyName)}</td><td>${row.profileCount}</td>
            <td>${escape(owner(row))}</td><td>${escape(scopeLabel(row))}</td><td>${versionLabel(row.latestNumber)} / ${versionLabel(row.defaultNumber)}</td>
            <td>${escape(formatDate(row.updatedAt))}</td><td>${btn('查看 / 编辑','open:'+row.id)}</td></tr>`).join('')}
          </tbody></table></div>${items.length ? '' : '<p class="panel-pad muted">没有匹配的知识库。</p>'}</section>
        <div class="toolbar mt12"><span class="hint">共 ${total} 个知识库</span><div class="toolbar-right">
          ${page > 1 ? btn('上一页','page:'+(page-1)) : ''}${page*pageSize < total ? btn('下一页','page:'+(page+1)) : ''}
        </div></div>`;
      this.root.querySelector('[data-kb-filter]').onsubmit = event => {
        event.preventDefault();
        const input = new FormData(event.currentTarget);
        Object.assign(filterState, {search:input.get('search'), taxonomy:input.get('taxonomy'),
          scope:input.get('scope'), archived:input.has('archived'), page:1});
        this.load();
      };
    }

    renderDetail() {
      const row = this.base;
      const version = this.snapshot?.version;
      const versionOptions = row.versions.map(value => option(value.id,
        value.state === 'draft' ? '草稿' : versionLabel(value.versionNo), value.id === version?.id)).join('');
      const operations = (row.canManage ? btn('设置','settings') : '') +
        (version?.state === 'saved' ? btn('创建副本','copy') : '') +
        (this.draft ? (row.canPublish ? btn('保存新版本','publish','primary') : '') :
          row.canEdit && version ? btn(row.currentDraftId ? '继续编辑草稿' : '基于此创建草稿','draft','primary') : '');
      this.root.innerHTML = `<div class="mini-nav">${btn('学科知识库','list','small ghost')} <span> / ${escape(row.name)}</span></div>` +
        this.heading(row.name, row.description || '', operations) + `
        <div class="meta-strip"><span>学科体系 <strong>${escape(row.taxonomyName)}</strong></span><span>所有者 <strong>${escape(owner(row))}</strong></span>
        <span>共享范围 <strong>${escape(scopeLabel(row))}</strong></span><span>${version?.state === 'draft' ? badge('草稿','amber') : badge('已保存 · 只读','green')}</span>
        ${versionOptions ? `<label>版本 <select id="kb-version-picker" class="filter-select">${versionOptions}</select></label>` : ''}</div>
        ${row.archivedAt ? note('此知识库已归档') : ''}
        ${version ? `<div class="toolbar"><div class="toolbar-left">${escape(pairs(Object.fromEntries(Object.keys(counts).map(key => [key,this.snapshot.validation[key]]))))}</div>
          <div class="toolbar-right">${btn('资源完整性检查','validate')}${btn('导出 JSON','export')}
          ${this.draft ? btn('导入 JSON','import','small secondary') : ''}</div></div>` : ''}
        <div class="tabbar">${Object.entries(tabs).map(([id,label]) =>
          `<button class="tab ${id === this.tab ? 'active' : ''}" data-kb-action="tab:${id}">${label}</button>`).join('')}</div>
        ${this.tab === 'versions' ? this.versions() : version ? this.renderResources() : '<p class="hint">暂无可查看的资源版本。</p>'}`;
    }

    versions() {
      return `<section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr>
        <th>版本</th><th>修改说明</th><th>保存人 / 时间</th><th>资源</th><th>操作</th></tr></thead><tbody>
        ${this.base.versions.map(row => `<tr><td>${row.state === 'draft' ? badge('草稿','amber') : badge(versionLabel(row.versionNo),'green')}
          ${row.id === this.base.defaultVersionId ? badge('默认') : ''}</td><td>${escape(row.changeNote || '—')}</td>
          <td>${escape(row.savedByLogin || '—')}<div class="hint">${escape(formatDate(row.savedAt))}</div></td>
          <td>画像 ${row.profileCount} · 摘要 ${row.summaryCount} · 簇 ${row.clusterCount}</td>
          <td>${btn('查看','version:'+row.id)}${row.state === 'saved' ? btn('导出','export:'+row.id) : ''}
          ${row.state === 'saved' && this.base.canManage && this.base.canPublish && row.id !== this.base.defaultVersionId
            ? btn('设为默认','default:'+row.id) : ''}</td></tr>`).join('')}</tbody></table></div></section>`;
    }

    renderResources() {
      const {resources, validation, assets} = this.snapshot;
      const names = new Map(resources.profiles.map(row => [row.code,row.name]));
      if (this.tab === 'profiles') return `<div class="toolbar"><span class="hint">从目录选择学科，查看其完整画像。</span>
        ${this.draft ? btn('添加学科','edit:profiles','small primary') : ''}</div>
        <div class="knowledge-grid"><section class="panel"><div class="directory-search"><input class="form-control"
          aria-label="查找学科" placeholder="查找学科名称或代码" data-kb-profile-search></div>
          <div class="directory" data-kb-profile-list>${this.profileList(resources.profiles)}</div></section>
          <section class="panel panel-pad" data-kb-portrait>${note(resources.profiles.length ? '请选择学科' : '暂无学科画像')}</section></div>`;
      if (this.tab === 'summaries') return `<div class="toolbar"><span>${validation.stale ? badge(validation.stale+' 条待更新','amber') : ''}</span>
        <div class="toolbar-right">${this.draft ? btn('提取摘要预览','generate:summaries') + btn('添加摘要','edit:summaries','small primary') : ''}</div></div>
        <section class="panel">${resources.summaries.map(row => `<div class="resource-row"><div class="grow">
          <div class="row-name">${escape(row.code)} ${escape(row.name)} ${row.stale ? badge('待更新','amber') : ''}
          ${row.provenanceStatus === 'imported_unknown' ? badge('导入摘要') : ''}</div><p class="row-sub">${escape(row.summary)}</p>
          ${note('关键词 ' + (row.keywords || []).join('、'))}</div>${this.editActions('summaries', row)}</div>`).join('') ||
          '<p class="panel-pad muted">暂无召回摘要。</p>'}</section>`;
      if (this.tab === 'clusters') return `<div class="toolbar"><span class="hint">共 ${resources.clusters.length} 个学科簇</span>
        <div class="toolbar-right">${this.draft ? btn('关键词分组预览','generate:clusters') + btn('新建簇','edit:clusters','small primary') : ''}</div></div>
        <div class="resource-card-grid">${resources.clusters.map(row => `<article class="resource-card">
          <div class="section-head">${badge(row.code,'green')}${this.editActions('clusters',row)}</div>
          <h3 class="resource-card-title">${escape(row.name)}</h3><p>${escape(row.description)}</p>
          <div class="pill-list">${row.members.map(code => `<span class="pill">${escape(names.get(code) || code)}</span>`).join('')}</div>
          ${note('关键词 ' + (row.keywords || []).join('、'))}</article>`).join('') || note('暂无学科簇')}</div>`;
      if (this.tab === 'papers') return `<div class="toolbar"><span class="hint">共 ${resources.papers.length} 篇种子论文</span>
        <div class="toolbar-right">${this.draft ? btn('导入 JSON','import') + btn('添加论文','edit:papers','small primary') : ''}</div></div>
        <section class="panel"><div class="table-wrap"><table class="data-table"><thead><tr><th>论文</th><th>所属学科</th><th>来源</th><th>操作</th></tr></thead>
          <tbody>${resources.papers.map(row => `<tr><td><strong>${escape(row.title)}</strong><div class="hint">${escape((row.authors || []).join('、'))}</div>
          <details><summary>摘要和出版信息</summary><p>${escape(row.abstract)}</p><p>${escape([row.journalName,row.publicationRaw,row.publicationYear,row.doiRaw].filter(Boolean).join(' · '))}</p></details>
          </td><td>${row.disciplines.map(relation => escape(names.get(relation.code) || relation.code) +
            (relation.useForGeneration ? '（参与生成）' : '')).join('、')}</td>
          <td>${escape([row.sourceSystem,row.sourceRecordId].filter(Boolean).join(' · '))}</td><td>${this.editActions('papers',row)}</td></tr>`).join('')}
          </tbody></table></div>${resources.papers.length ? '' : '<p class="panel-pad muted">暂无种子论文。</p>'}</section>`;
      return `<div class="toolbar"><span class="hint">相似度资源以附件管理。</span>${this.draft ? btn('上传附件','attach','small primary') : ''}</div>
        <section class="panel">${assets.map(row => `<div class="resource-row"><div class="grow"><strong>${escape(row.name)}</strong>
          ${note(row.description || '')}${note(row.byteSize + ' 字节 · ' + row.contentSha256)}</div>
          <div class="card-actions"><a class="button small" href="${this.versionUrl()}/assets/${row.id}">下载</a>
          ${this.draft ? btn('替换','attach:'+row.id) + btn('移除','remove:assets:'+row.id,'small ghost') : ''}</div></div>`).join('') ||
          '<p class="panel-pad muted">暂无相似度附件。</p>'}</section>`;
    }

    editActions(kind, row) {
      return this.draft ? `<div class="card-actions">${btn('编辑','edit:'+kind+':'+row.id)}
        ${btn('移除','remove:'+kind+':'+row.id,'small ghost')}</div>` : '';
    }

    profileList(rows, selected) {
      return rows.map(row => `<button class="directory-row ${row.id === selected ? 'active' : ''}" data-kb-action="portrait:${row.id}">
        <span class="mono tiny muted">${escape(row.code)}</span> ${escape(row.name)}</button>`).join('');
    }

    async selectProfile(id) {
      const list = this.root.querySelector('[data-kb-profile-list]');
      if (!list) return;
      this.selectedProfile = id;
      const all = this.snapshot.resources.profiles;
      const search = this.root.querySelector('[data-kb-profile-search]');
      const update = () => {
        const value = search.value.trim().toLowerCase();
        list.innerHTML = this.profileList(all.filter(row => (row.code + ' ' + row.name).toLowerCase().includes(value)),this.selectedProfile);
      };
      search.oninput = update;
      update();
      const panel = this.root.querySelector('[data-kb-portrait]');
      panel.textContent = '正在加载画像…';
      try {
        const row = await request(this.versionUrl() + '/portraits/' + id);
        if (!this.root.isConnected || this.selectedProfile !== id) return;
        this.portrait = row;
        const raw = row.portrait || {};
        panel.innerHTML = `<div class="section-head"><div><h2 class="section-title">${escape(row.name)}</h2>
          <span class="hint">${escape(row.code)} ${escape(row.sourceVersion || '')}</span></div>${this.editActions('profiles',row)}</div>
          ${row.textSyncStatus === 'needs_review' ? badge('精判文本待核对','amber') : ''}
          <h3>原始定义</h3><p class="kb-text">${escape(raw.original_definition || '—')}</p>
          ${Object.entries(tasks).map(([key,label]) => `<h3>${label} ${badge((raw[key] || []).length)}</h3>
            ${(raw[key] || []).map((item,i) => `<details><summary>${escape(item.task_name || item.direction_name || item.rule_id || item.task_id || '条目 '+(i+1))}</summary>
              <pre class="kb-text">${escape(pretty(item))}</pre></details>`).join('')}`).join('')}
          <details class="mt12"><summary>算法使用文本</summary><pre class="kb-text">${escape(row.judgmentText || '—')}</pre></details>
          <details><summary>完整画像 JSON</summary><pre class="kb-text">${escape(pretty(raw))}</pre></details>`;
      } catch (error) { if (this.selectedProfile === id) panel.textContent = errorText(error); }
    }

    dialog(title, body, submitLabel, submit) {
      const dialog = document.createElement('dialog');
      dialog.className = 'kb-dialog';
      dialog.setAttribute('aria-label', title);
      dialog.innerHTML = `<form><div class="modal-header"><strong>${escape(title)}</strong></div>
        <div class="modal-body">${body}<p class="text-danger" data-kb-error role="alert"></p></div>
        <div class="modal-footer"><button class="button" type="button" data-kb-close>${submit ? '取消' : '关闭'}</button>
        ${submit ? `<button class="button primary" type="submit">${escape(submitLabel)}</button>` : ''}</div></form>`;
      this.root.append(dialog);
      const form = dialog.querySelector('form');
      dialog.querySelector('[data-kb-close]').onclick = () => dialog.close();
      dialog.addEventListener('close', () => dialog.remove(), {once:true});
      if (submit) form.onsubmit = async event => {
        event.preventDefault();
        const button = form.querySelector('[type=submit]');
        const errorLine = form.querySelector('[data-kb-error]');
        button.disabled = true;
        errorLine.textContent = '';
        try {
          await submit(new FormData(form), form);
          dialog.close();
        } catch (error) {
          errorLine.textContent = errorText(error);
        } finally {
          button.disabled = false;
        }
      };
      dialog.showModal();
    }

    async action(command) {
      const [name, arg, index] = command.split(':');
      switch (name) {
        case 'reload': return this.load();
        case 'list': return this.navigate('knowledge');
        case 'page': filterState.page = Number(arg); return this.load();
        case 'open': return this.open(arg);
        case 'tab': return this.open(this.base.id,this.snapshot?.version.id || '',arg,this.portrait?.code || '');
        case 'version': return this.open(this.base.id,arg);
        case 'portrait': return this.selectProfile(Number(arg));
        case 'create': return this.create(arg === 'import');
        case 'settings': return this.settings();
        case 'archive': return this.archive();
        case 'draft': return this.createDraft();
        case 'copy': return this.copy();
        case 'publish': return this.publish();
        case 'default':
          await request(this.versionUrl(arg) + '/default','PUT');
          this.toast('已设为默认版本');
          return this.load();
        case 'export': {
          const version = arg || this.snapshot.version.id;
          const data = await request(this.versionUrl(version) + '/export');
          return download(`知识库-${this.base.taxonomyCode}-${version}.json`, data);
        }
        case 'validate': return this.validation();
        case 'edit': return this.edit(arg,index ? Number(index) : null);
        case 'remove': return this.remove(arg,Number(index));
        case 'import': return this.import();
        case 'attach': return this.attach(arg ? Number(arg) : null);
        case 'generate': return this.generate(arg);
      }
    }

    create(importing = false) {
      this.dialog(importing ? '创建知识库并导入' : '创建知识库', fields('知识库名称','name','',{required:true}) + fields('说明','description','',{rows:3}) +
        select('学科体系','taxonomy',this.catalog.taxonomies.map(row => option(row.code,row.name)).join('')),
      '创建草稿', async input => {
        const result = await request(api,'POST',Object.fromEntries(input));
        if (importing) pendingImportFor = result.id;
        this.toast('知识库草稿已创建');
        this.open(result.id,result.currentDraftId);
      });
    }

    async settings() {
      const catalog = await request(api);
      const row = this.base;
      const builtin = row.visibility === 'builtin';
      this.dialog('知识库设置', fields('知识库名称','name',row.name,{required:true}) +
        fields('说明','description',row.description,{rows:3}) +
        (builtin ? note('共享范围 内置') : select('共享范围','visibility',
          option('private','个人',row.visibility === 'private') + option('team','团队共享',row.visibility === 'team')) +
          select('团队','teamId',option('0','请选择团队') + catalog.teams.map(team => option(team.id,team.name,team.id === row.teamId)).join(''))) +
        btn(row.archivedAt ? '恢复知识库' : '归档知识库','archive','small ghost'),
      '保存设置', async input => {
        await request(api + '/' + row.id,'PUT',{name:input.get('name'),description:input.get('description'),
          visibility:builtin ? 'builtin' : input.get('visibility'), teamId:Number(input.get('teamId') || 0)});
        this.toast('设置已保存');
        this.load();
      });
    }

    archive() {
      const archived = !this.base.archivedAt;
      const label = archived ? '归档知识库' : '恢复知识库';
      this.root.querySelector('dialog[open]')?.close();
      this.dialog(label,note(archived ? '归档后仅所有者可在已归档列表查看。' : '恢复后将按共享范围展示。'),label,async () => {
        await request(api + '/' + this.base.id + '/archive','PUT',{archived});
        this.toast(archived ? '已归档' : '已恢复');
        this.navigate('knowledge');
      });
    }

    async createDraft() {
      const draftId = this.base.currentDraftId;
      if (draftId) return this.open(this.base.id,draftId,this.tab);
      const data = await request(this.draftUrl(),'POST',{versionId:this.snapshot.version.id});
      this.toast('草稿已创建');
      this.open(this.base.id,data.currentDraftId,this.tab);
    }

    copy() {
      this.dialog('从已保存版本创建副本',fields('副本名称','name',this.base.name+' · 副本',{required:true}),
        '创建副本',async input => {
          const data = await request(this.versionUrl() + '/copy','POST',{name:input.get('name')});
          this.toast('副本草稿已创建');
          this.open(data.id,data.currentDraftId);
        });
    }

    validation() {
      const validation = this.snapshot.validation;
      this.dialog('资源完整性检查', note(pairs(Object.fromEntries(Object.keys(counts).map(key => [key,validation[key]])))) +
        (validation.compatible ? badge('满足当前算法资源要求','green') :
          `<ul class="kb-issues">${validation.issues.map(message => `<li>${escape(message)}</li>`).join('')}</ul>`));
    }

    publish() {
      this.dialog('保存知识库新版本',fields('版本修改说明','changeNote','',{required:true,rows:3}) +
        (!this.snapshot.validation.compatible ? `<label class="checkbox-line"><input type="checkbox" name="allowIncomplete" required>
          确认保存为暂不满足当前算法运行条件的版本</label>` : ''),'保存新版本',async input => {
        const data = await request(this.draftUrl('/publish'),'POST',this.draftInput({
          changeNote:input.get('changeNote'),allowIncomplete:input.has('allowIncomplete')}));
        this.toast('已保存 ' + versionLabel(data.versionNo));
        this.open(this.base.id,data.versionId,'versions');
      });
    }

    async save(kind,item) {
      await request(this.draftUrl('/resources/'+kind),'PUT',this.draftInput({item}));
      this.toast('已保存到草稿');
      if (kind === 'profiles') this.open(this.base.id,this.snapshot.version.id,'profiles',item.code);
      else this.load();
    }

    edit(kind, id) {
      if (!this.draft) return;
      const resources = this.snapshot.resources;
      const existing = id ? resources[kind].find(row => row.id === id) : null;
      const item = existing || {};
      if (kind === 'profiles') return this.editProfile(id);
      if (kind === 'summaries') {
        const profiles = existing ? resources.profiles.filter(row => row.code === item.code) :
          resources.profiles.filter(row => !resources.summaries.some(summary => summary.code === row.code));
        if (!profiles.length) return this.toast('当前草稿没有可添加摘要的学科',true);
        return this.dialog('编辑召回摘要',select('学科','code',profiles.map(row => option(row.code,row.code+' '+row.name)).join('')) +
          fields('摘要','summary',item.summary,{rows:5,required:true}) + fields('关键词（每行一个）','keywords',words(item.keywords),{rows:4}),
        '保存草稿',input => this.save(kind,{id,code:input.get('code'),summary:input.get('summary'),keywords:lines(input.get('keywords'))}));
      }
      if (kind === 'clusters') {
        const members = new Set(item.members || []);
        return this.dialog('编辑学科簇',fields('簇代码','code',item.code,{required:true}) + fields('簇名称','name',item.name,{required:true}) +
          fields('簇描述','description',item.description,{rows:3}) + fields('关键词（每行一个）','keywords',words(item.keywords),{rows:3}) +
          `<label class="field"><span class="label">成员学科</span><select class="form-control" name="members" multiple size="8">
          ${resources.profiles.map(row => option(row.code,row.code+' '+row.name,members.has(row.code))).join('')}</select></label>`,
        '保存草稿',input => this.save(kind,{id,code:input.get('code'),name:input.get('name'),description:input.get('description'),
          keywords:lines(input.get('keywords')),members:input.getAll('members')}));
      }
      if (kind === 'papers') return this.editPaper(item,id);
    }

    editProfile(id) {
      const current = id ? this.portrait : null;
      const raw = current?.portrait || {};
      const taskFields = Object.entries(tasks).map(([key,label]) =>
        fields(label+'（JSON 数组）',key,pretty(raw[key] || []),{rows:4})).join('');
      this.dialog('编辑学科画像',fields('学科代码','code',current?.code,{required:true,readonly:!!id}) +
        fields('学科名称','name',current?.name,{required:true}) + fields('原始定义','original_definition',raw.original_definition,{rows:3}) +
        taskFields + fields('主体精判文本','final_definition',raw.final_definition,{rows:5}) +
        fields('扩充画像文本','expanded_portraits',raw.expanded_portraits,{rows:3}) +
        `<label class="checkbox-line"><input type="checkbox" name="reviewed"> 我已核对精判文本与画像结构一致</label>`,
      '保存草稿',async input => {
        const portrait = {...raw};
        Object.keys(tasks).forEach(key => {
          try { portrait[key] = JSON.parse(input.get(key)); }
          catch (_) { throw new Error(tasks[key] + '的 JSON 格式无效'); }
        });
        for (const key of ['original_definition','final_definition','expanded_portraits']) {
          if (input.get(key) || Object.hasOwn(raw,key)) portrait[key] = input.get(key);
        }
        await this.save('profiles',{id,code:input.get('code'),name:input.get('name'),portrait,reviewed:input.has('reviewed')});
      });
    }

    editPaper(item,id) {
      const relations = new Map((item.disciplines || []).map(row => [row.code,row.useForGeneration]));
      const keys = {journalName:'期刊或会议',publicationRaw:'原始出版信息',publicationYear:'年份',
        doiRaw:'原始 DOI',sourceSystem:'来源系统',sourceRecordId:'原始文献编号'};
      this.dialog('编辑种子论文',fields('论文标题','title',item.title,{required:true}) + fields('摘要','abstract',item.abstract,{rows:4}) +
        fields('作者（每行一个）','authors',words(item.authors),{rows:3}) + fields('关键词（每行一个）','keywords',words(item.keywords),{rows:3}) +
        Object.entries(keys).map(([key,label]) => fields(label,key,item[key],{type:key === 'publicationYear' ? 'number' : 'text'})).join('') +
        `<div class="label">所属学科及参与生成设置</div><div class="kb-paper-links">${this.snapshot.resources.profiles.map(row => `
          <div><label><input name="discipline" type="checkbox" value="${escape(row.code)}" ${relations.has(row.code)?'checked':''}>
          ${escape(row.code)} ${escape(row.name)}</label><label><input name="generation" type="checkbox" value="${escape(row.code)}"
          ${relations.get(row.code) === true ? 'checked' : ''}> 参与生成</label></div>`).join('')}</div>`,
      '保存草稿',input => {
        const values = Object.fromEntries(Object.keys(keys).map(key => [key,input.get(key)]));
        values.publicationYear = values.publicationYear ? Number(values.publicationYear) : null;
        const participates = new Set(input.getAll('generation'));
        return this.save('papers',{...values,id,title:input.get('title'),abstract:input.get('abstract'),rawRecord:item.rawRecord,
          authors:lines(input.get('authors')),keywords:lines(input.get('keywords')),
          disciplines:input.getAll('discipline').map(code => ({code,useForGeneration:participates.has(code)}))});
      });
    }

    remove(kind,id) {
      this.dialog('移除草稿资源',note('确定从当前草稿移除此资源？'),'移除',async () => {
        await request(this.draftUrl('/resources/'+kind+'/'+id),'DELETE',this.draftInput());
        this.toast('已从草稿移除');
        this.load();
      });
    }

    import() {
      const template = {format:'discipline-knowledge-1',taxonomy:this.base.taxonomyCode,
        resources:{profiles:[],summaries:[],clusters:[],papers:[]}};
      this.dialog('导入知识资源',note('选择本系统导出的知识库 JSON，或按同一结构准备资源。预览会列出重复代码和缺失关联。') +
        `<label class="field"><span class="label">JSON 文件</span><input type="file" name="upload" accept=".json,application/json" required></label>
        <details><summary>导入文件结构</summary><pre>${escape(pretty(template))}</pre></details>`,
      '检查并预览',async input => {
        const upload = await readUpload(input.get('upload'),32);
        const payload = this.draftInput(upload);
        const preview = await request(this.draftUrl('/import'),'POST',payload);
        const body = note(preview.name) + note(pairs(preview.counts)) +
          (preview.issues.length ? `<ul class="kb-issues">${preview.issues.map(issue => `<li>${escape(issue)}</li>`).join('')}</ul>` :
            badge('导入检查通过','green'));
        this.dialog('导入预览',body,'确认写入草稿',preview.valid ? async () => {
          await request(this.draftUrl('/import'),'POST',{...payload,apply:true,previewHash:preview.sha256});
          this.toast('资源已导入草稿');
          this.load();
        } : null);
      });
    }

    attach(id) {
      const row = this.snapshot.assets.find(asset => asset.id === id);
      this.dialog(id ? '替换相似度附件' : '上传相似度附件',
        `<label class="field"><span class="label">CSV、TSV 或 JSON 文件</span>
        <input type="file" name="upload" accept=".csv,.tsv,.json" required></label>` +
        fields('来源与适用范围','description',row?.description,{rows:3}),'保存附件',async input => {
        const upload = await readUpload(input.get('upload'),8);
        await request(this.draftUrl('/assets'),'POST',this.draftInput({...upload,replaceId:id,description:input.get('description')}));
        this.toast('附件已保存到草稿');
        this.load();
      });
    }

    async generate(kind) {
      const payload = this.draftInput();
      const url = this.draftUrl('/generate/'+kind);
      const result = await request(url,'POST',payload);
      const summary = kind === 'summaries';
      const description = summary ? '从画像定义摘取前 220 字，并取核心任务名称作为关键词。确认后更新对应学科的摘要。' :
        '按摘要的首个关键词分组，缺少关键词的学科按名称分组。确认后替换草稿中的学科簇。';
      this.dialog(summary ? '摘要提取预览' : '学科簇分组预览',note(description) + note('共 '+result.items.length+' 条') +
        `<div class="kb-issues">${result.items.map(row => `<p><strong>${escape(row.code)} ${escape(row.name || '')}</strong>
          ${escape(summary ? row.summary : row.members.join('、'))}</p>`).join('')}</div>`,
      '确认写入草稿',result.items.length ? async () => {
        await request(url,'POST',{...payload,apply:true});
        this.toast('生成结果已写入草稿');
        this.load();
      } : null);
    }
  }

  window.ClassifierKnowledge = Object.freeze({
    search(query) {
      Object.assign(filterState,{search:query,page:1});
    },
    async catalog() {
      const first = await request(api);
      const pages = Math.ceil(first.total / first.pageSize);
      const rest = await Promise.all(Array.from({length:Math.max(0,pages-1)}, (_,i) => request(api + '?page=' + (i+2))));
      return [first,...rest].flatMap(page => page.items).map(row => ({...row,id:String(row.id),
        defaultVersion:row.defaultNumber == null ? '' : versionLabel(row.defaultNumber)}));
    },
    async versions(id) {
      const row = await request(api + '/' + encodeURIComponent(id));
      return row.versions.filter(version => version.state === 'saved').map(version => ({number:versionLabel(version.versionNo)}));
    },
    mount(root, route, navigate, toast) {
      return new KnowledgePage(root,{...route},navigate,toast).load();
    }
  });
})();

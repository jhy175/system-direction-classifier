(function () {
'use strict';

const stages = [
  ['parse', '输入解析', 'file'], ['router', '簇路由', 'workflow'], ['recall', '学科召回', 'search'],
  ['judge', '独立精判', 'checkcircle'], ['post', '后处理', 'filter'], ['result', '学科结果', 'tag']
];
const labels = {
  pending: '等待执行', running: '运行中', succeeded: '完成', failed: '失败', reused: '复用前置结果',
  needs_rerun: '待重算', skipped: '未执行', unknown: '状态未知'
};
const date = value => value ? new Date(value).toLocaleString('zh-CN', {hour12: false}) : '';
const emptyInput = () => ({type: '学术论文', title: '', keywords: '', abstract: '', journal: '', doi: '', year: '', externalId: ''});
const sample = {
  ...emptyInput(), title: '融合遥感影像与机器学习的城市热岛识别',
  keywords: '城市热岛；遥感；地表温度；随机森林',
  abstract: '本文利用多时相卫星遥感影像反演城市地表温度，结合土地覆盖、植被指数和建筑密度训练随机森林模型。研究比较不同城市功能区热岛强度，分析绿地空间格局对局地降温的影响，并评估模型在不同季节的识别表现。'
};

async function request(path, method = 'GET', body) {
  const headers = {Accept: 'application/json'};
  if (method !== 'GET') {
    const response = await fetch('/api/auth/csrf', {credentials: 'same-origin'});
    const result = await response.json();
    if (!response.ok || !result.data?.csrfToken) throw new Error(result.message || '请求校验失效，请刷新页面');
    headers['X-CSRF-Token'] = result.data.csrfToken;
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch('/api/debug' + path, {
    method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body)
  });
  const result = await response.json();
  if (!response.ok || !result.success) throw new Error(result.message || '调试请求失败');
  return result.data;
}

window.ClassifierDebug = Object.freeze({
  create(ui, actions, view) {
    const {render, notify, openModal, closeModal, renderModal, openMethod, download} = actions;
    const {esc, btn, badge, field, opt, note, ic, empty, pageHead} = view;
    let run = null, detail = null, stage = 'parse', showCalls = false, busy = false;
    let routeKey = '', runError = '', detailError = '', detailPending = false, sourceBatchRunId = null;
    let poll = 0, routeStamp = 0, detailStamp = 0;
    let history = null, historyError = '', historySearch = '', choices = [], historyStamp = 0;

    const terminal = () => run && ['succeeded', 'failed'].includes(run.status);
    const isLocked = () => Boolean(run || ui.dbgRunId || ui.dbgHistoryId || busy);
    const stageLabel = code => stages.find(item => item[0] === code)?.[1] || code;
    const status = value => badge(labels[value] || value || '等待执行',
      ['succeeded', 'reused'].includes(value) ? 'green' : value === 'failed' ? 'red' : value === 'running' ? 'amber' : 'neutral');
    const buttons = (label, action, style = 'small', disabled = false) =>
      disabled ? `<button type="button" class="button ${style}" disabled>${label}</button>` : btn(label, action, style);
    const redraw = () => { if (ui.page === 'debug') render(); };
    const fromRoute = () => ui.dbgHistoryId ? 'history:' + ui.dbgHistoryId : ui.dbgRunId ? 'run:' + ui.dbgRunId : '';

    function pollRun() {
      clearTimeout(poll);
      if (!run || terminal() || ui.dbgHistoryId) return;
      const id = run.id, stamp = routeStamp;
      poll = setTimeout(async () => {
        try {
          const next = await request(`/runs/${id}`);
          if (stamp !== routeStamp) return;
          const previous = run.stages.find(row => row.stageCode === stage)?.status;
          const current = next.stages.find(row => row.stageCode === stage)?.status;
          const changed = run.status !== next.status || JSON.stringify(run.stages) !== JSON.stringify(next.stages);
          run = next;
          runError = '';
          if (changed) redraw();
          if (previous !== current || (showCalls && current === 'running')) loadStage();
        } catch (error) {
          if (stamp !== routeStamp) return;
          runError = error.message;
          redraw();
        }
        pollRun();
      }, 1200);
    }

    function show(next, historical = false) {
      run = next;
      ui.dbgMethodId = String(next.methodId);
      ui.dbgVersion = 'v' + next.methodVersionNo;
      ui.dbgRunId = historical ? '' : String(next.id);
      ui.dbgHistoryId = historical ? String(next.historyId) : '';
      routeKey = fromRoute();
      runError = '';
      stage = next.failedStageCode || next.startStageCode || 'parse';
      showCalls = false;
      detail = null;
      redraw();
      loadStage();
      pollRun();
    }

    function reset(input = emptyInput(), source = null) {
      clearTimeout(poll);
      routeStamp++;
      detailStamp++;
      routeKey = '';
      run = null;
      detail = null;
      runError = '';
      ui.dbgRunId = '';
      ui.dbgHistoryId = '';
      ui.dbgInput = {...emptyInput(), ...input};
      sourceBatchRunId = source;
      redraw();
    }

    async function route() {
      const key = fromRoute();
      if (routeKey === key) return;
      clearTimeout(poll);
      detailStamp++;
      const stamp = ++routeStamp;
      routeKey = key;
      run = null;
      detail = null;
      runError = '';
      redraw();
      if (!key) return;
      try {
        const next = await request(ui.dbgHistoryId ? `/history/${ui.dbgHistoryId}` : `/runs/${ui.dbgRunId}`);
        if (stamp === routeStamp) show(next, Boolean(ui.dbgHistoryId));
      } catch (error) {
        if (stamp === routeStamp) { runError = error.message; redraw(); }
      }
    }

    async function loadStage() {
      if (!run) return;
      const stamp = ++detailStamp;
      detailPending = true;
      detailError = '';
      try {
        const next = await request(`/runs/${run.id}/stages/${stage}?calls=${showCalls}`);
        if (stamp === detailStamp) detail = next;
      } catch (error) {
        if (stamp === detailStamp) detailError = error.message;
      } finally {
        if (stamp === detailStamp) { detailPending = false; redraw(); }
      }
    }

    async function runAction(work) {
      if (busy) return;
      busy = true;
      redraw();
      try { await work(); } catch (error) { notify(error.message, true); }
      finally { busy = false; redraw(); }
    }

    const branch = (mode, stageCode, extra = {}) => runAction(async () => {
      const next = await request(`/runs/${run.id}/branch`, 'POST', {mode, stage: stageCode, ...extra});
      ++routeStamp;
      closeModal();
      show(next);
    });

    async function loadHistory(page = 1, search = historySearch) {
      const stamp = ++historyStamp;
      historySearch = search;
      history = null;
      historyError = '';
      openModal('debugHistory', {search});
      try {
        const next = await request(`/history?search=${encodeURIComponent(search)}&page=${page}`);
        if (stamp !== historyStamp) return;
        history = next;
      } catch (error) {
        if (stamp !== historyStamp) return;
        historyError = error.message;
      }
      if (ui.modal === 'debugHistory') renderModal();
    }

    function historyView() {
      const heading = `<div class="card-actions">${field('标题或方法', ui.modalValues.search, 'modal:search')}
        ${btn('查询', 'debug:searchHistory', 'small')}</div>`;
      if (historyError) return heading + note(esc(historyError), 'red');
      if (!history) return heading + '<p class="hint" role="status">正在读取调试历史…</p>';
      if (!history.items.length) return heading + empty('暂无调试记录', '保存终态运行后，可在这里查看快照。');
      const rows = history.items.map(row => `<div class="row-item"><div class="grow">
        <strong>${esc(row.title || '未填写标题')}</strong>
        <div class="row-sub">${esc(row.methodName)} v${esc(row.methodVersionNo)} · ${esc(row.knowledgeName)} v${esc(row.knowledgeVersionNo)}</div>
        <div class="row-sub">${esc(row.mainDiscipline || '无主学科')} · 交叉度 ${esc(row.metricValue ?? '未提供')} · ${esc(date(row.savedAt))}
          ${row.actorName ? ' · ' + esc(row.actorName) : ''}</div>
        ${status(row.status)} ${row.manualCount ? badge('人工调整', 'amber') : ''}
        </div>${btn('查看快照', `debug:openHistory:${row.id}`, 'small')}</div>`).join('');
      return heading + rows + `<div class="card-actions mt12">
        ${buttons('上一页', 'debug:historyPage:' + (history.page - 1), 'small', history.page <= 1)}
        <span class="hint">第 ${history.page} 页 · 共 ${history.total} 条</span>
        ${buttons('下一页', 'debug:historyPage:' + (history.page + 1), 'small', history.page * history.pageSize >= history.total)}</div>`;
    }

    function candidateView() {
      return field('当前知识版本内的学科', ui.modalValues.disciplineId, 'modal:disciplineId', {
        type: 'select', options: [['', '请选择学科'], ...choices.map(item => [item.id, `${item.code} ${item.name}`])]
      }) + note('添加后将创建新运行，从独立精判开始执行。');
    }

    function inputPanel() {
      const input = run?.input || ui.dbgInput;
      const locked = isLocked();
      return `<section class="panel debug-form"><div class="section-head"><strong class="section-title">论文输入</strong>
        <div class="card-actions">${locked ? (run && terminal() ? btn('载入输入编辑', 'debug:edit', 'small') : '') :
          btn('填入示例', 'debug:sample', 'small ghost') + btn('清空', 'debug:clear', 'small ghost')}</div></div>
        ${field('内容类型', input.type, 'debug:type', {type: 'select', disabled: locked, options: ['学术论文','项目说明','研究报告','其他科研文本']})}
        ${field('标题', input.title, 'debug:title', {disabled: locked, placeholder: '输入论文标题'})}
        ${field('关键词', input.keywords, 'debug:keywords', {disabled: locked, placeholder: '多个关键词可用分号分隔'})}
        ${field('摘要', input.abstract, 'debug:abstract', {type: 'textarea', rows: 7, disabled: locked, placeholder: '粘贴论文摘要'})}
        ${input.text ? field('拼接原文', input.text, 'debug:text', {type: 'textarea', disabled: locked}) : ''}
        <button class="advanced-toggle" data-action="debug:more">更多信息 ${ic(ui.dbgMore ? 'up' : 'down', 15)}</button>
        ${ui.dbgMore ? `<div class="field-grid">${[['期刊 / 会议','journal'],['出版信息','year'],['DOI','doi'],['外部编号','externalId']]
          .map(([label, key]) => field(label, input[key], 'debug:' + key, {disabled: locked})).join('')}</div>` : ''}</section>`;
    }

    function resultPanel() {
      if (runError) return note(esc(runError), 'red') + btn('重新加载', 'debug:reload', 'small');
      if (!run) return (ui.dbgRunId || ui.dbgHistoryId || busy) ? '<p class="hint" role="status">正在加载运行…</p>'
        : empty('等待运行识别', '填写论文信息并运行后，可查看学科结果和各阶段的输入输出。');
      if (!terminal()) return `<div role="status">${status(run.status)}<p class="hint">正在按所选方法执行。可以离开本页，稍后通过当前地址继续查看。</p></div>`;
      if (!run.result) return `<div>${status(run.status)}<p class="hint" role="alert">${esc(run.errorMessage || '运行没有可用的识别结果')}</p></div>`;
      const subjects = run.result.disciplines;
      const metric = run.result.metrics.find(row => row.metricCode === 'ICI');
      return `<div class="result-head"><div><div class="section-sub">主学科</div>
        <div class="row-name">${esc(subjects[0]?.name || '无保留学科')}</div></div>
        <div><div class="section-sub">交叉度 ICI</div><div class="row-name">${esc(metric?.metricValue ??
          ({failed:'接收失败', needs_update:'待更新'}[metric?.status] || '未提供'))}</div></div></div>
        ${subjects.map(row => `<div class="row-item"><div class="grow"><strong>${esc(row.finalRank)}. ${esc(row.name)} ${esc(row.code)}</strong>
          <div class="hint">${esc(row.reason)}</div></div>${badge(row.finalLevel, row.finalLevel === '高度相关' ? 'green' : 'neutral')}</div>`).join('')}`;
    }

    const raw = value => `<pre class="code-raw debug-code">${esc(typeof value === 'string' ? value : JSON.stringify(value, null, 2))}</pre>`;
    const rawDetails = (title, value) => value == null ? '' : `<details class="mt8"><summary>${esc(title)}</summary>${raw(value)}</details>`;
    function table(head, rows) {
      if (!rows.length) return '<p class="hint">没有条目。</p>';
      return `<div class="debug-table"><table class="step-detail-table"><thead><tr>${head.map(x => `<th>${esc(x)}</th>`).join('')}</tr></thead>
        <tbody>${rows.map(row => `<tr>${row.map(x => `<td>${esc(x)}</td>`).join('')}</tr>`).join('')}</tbody></table></div>`;
    }

    function stageOutput(output, phase = stage) {
      if (!output) return '<p class="hint">当前阶段暂无输出。</p>';
      const name = row => `${row.discipline_name} (${row.discipline_code})`;
      if (phase === 'parse') return table(['字段','实际内容','来源'], Object.entries(output.parsed_fields || {}).map(([key, value]) =>
        [key, value, output.field_sources?.[key]])) + rawDetails('实际召回文本', output.recall_text);
      if (phase === 'router') return table(['次序','学科簇','路由等级','理由','成员代码'], (output.selected_clusters || [])
        .map(row => [row.route_rank, row.cluster_name, row.route_level, row.route_reason, row.member_codes.join('、')]));
      if (phase === 'recall') return table(['名次','候选学科','来源','召回等级','分数','理由'], (output.selected_candidates || [])
        .map(row => [row.recall_rank, name(row), ({model:'模型',cluster_fill:'簇内补足',global_fallback:'全库兜底'}[row.candidate_source]),
          row.recall_level, row.effective_score, row.recall_reason]));
      if (phase === 'judge') return table(['候选学科','精判等级','理由','置信度','边界解释'], (output.judgments || [])
        .map(row => [name(row), row.judgment_failed ? '精判失败' : row.raw_level, row.reason || row.error_message, row.confidence, row.boundary_explanation]));
      const rows = phase === 'post' ? output.complete_ranking : output.disciplines;
      return table(['学科','原始等级','最终等级','处理前名次','处理后名次','决定'], (rows || [])
        .map(row => [name(row), row.raw_level, row.final_level, row.rank_before, row.rank_after,
          [row.adjustment_reason, row.included ? '保留' : row.filter_reason].filter(Boolean).join(' · ')]));
    }

    function callRows(trace = detail, phase = stage, enabled = showCalls) {
      if (!enabled) return '';
      if (!trace?.calls?.length) return '<p class="hint">当前阶段暂无模型调用记录。</p>';
      return trace.calls.map(call => `<details class="debug-call"><summary>${esc(call.disciplineName || stageLabel(phase))}
        · ${esc(call.modelId)} · 调用 ${call.logicalCallNo} / 尝试 ${call.attemptNo} · ${esc(labels[call.status])}
        ${call.durationMs == null ? '' : ' · ' + esc(call.durationMs) + ' ms'}</summary>
        ${call.errorMessage ? note(esc(call.errorMessage), 'red') : ''}
        ${call.endpointSnapshot ? `<p class="hint">${esc(call.endpointSnapshot)}${call.providerRequestId ? ' · ' + esc(call.providerRequestId) : ''}</p>` : ''}
        ${rawDetails('实际请求与提示词', call.request)}
        ${rawDetails('原始响应', call.rawResponse)}
        ${rawDetails('解析结果', call.parsedResponse)}
        ${rawDetails('用量', call.usage)}</details>`).join('');
    }

    function renderEvidence(phase, trace, calls) {
      return stageOutput(trace?.output, phase) + rawDetails('完整阶段输入', trace?.input) +
        rawDetails('完整阶段输出', trace?.output) + callRows(trace, phase, calls);
    }

    function process() {
      if (!run) return '';
      const info = run.stages.find(item => item.stageCode === stage);
      const start = stages.findIndex(item => item[0] === stage);
      const canResume = terminal() && run.stages.slice(0, start).every(item => ['succeeded','reused'].includes(item.status));
      const canAdd = terminal() && run.stages.slice(0, 3).every(item => ['succeeded','reused'].includes(item.status));
      return `<div class="section-head section-space"><h2 class="section-title">运行过程</h2></div>
        <div class="step-grid debug-stage-grid">${stages.map(([code, label, icon]) => `<button class="step-card ${code === stage ? 'active' : ''}"
          data-action="debug:step:${code}">${ic(icon,17)} <span class="step-name">${label}</span>
          ${status(run.stages.find(item => item.stageCode === code)?.status)}</button>`).join('')}</div>
        <section class="panel mt18"><div class="panel-head"><strong class="panel-title">${esc(stageLabel(stage))} · 过程详情</strong>
          <div class="card-actions">${['router','recall','judge'].includes(stage) ? btn(showCalls ? '收起调用详情' : '调用详情', 'debug:calls', 'small') : ''}
            ${info?.status === 'failed' && canResume ? buttons('重试失败阶段', 'debug:retry', 'small', busy) : ''}
            ${canResume ? buttons('从此阶段重跑', 'debug:resume', 'small secondary', busy) : ''}
            ${canAdd ? buttons('加入候选学科', 'debug:candidate', 'small', busy) : ''}</div></div>
          <div class="detail-block">${info?.errorMessage ? note(esc(info.errorMessage), 'red') : ''}
            ${detail?.sourceRunId ? note(`复用运行 #${esc(detail.sourceRunId)} 的阶段结果`) : ''}
            ${detailError ? note(esc(detailError), 'red') : ''}
            ${detailPending && !detail ? '<p class="hint">正在读取阶段详情…</p>' : renderEvidence(stage, detail, showCalls)}</div></section>`;
    }

    function page(methods, methodsError) {
      const method = methods.find(item => item.id === ui.dbgMethodId);
      const version = method?.versions.find(item => item.number === ui.dbgVersion);
      const title = pageHead('', '单篇调试', '选择已保存的方法版本，运行论文识别并查看各阶段依据。',
        buttons(ic('history', 15) + ' 调试历史', 'debug:history', 'secondary', busy)
        + buttons(ic('play', 15) + ' 运行识别', 'debug:run', 'primary', !version || isLocked()));
      const snapshot = run ? `<div class="snapshot-bar"><div><strong>${ui.dbgHistoryId ? '正在查看历史快照' : '运行 #' + esc(run.id)}</strong>
        ${status(run.status)} <span class="smalltext muted">${esc(date(run.createdAt))}</span>
        ${run.overrides?.manualDisciplineIds?.length ? badge('人工加入候选', 'amber') : ''}</div>
        <div class="card-actions">${terminal() ? buttons('重新运行', 'debug:full', 'small', busy) : ''}
          ${terminal() && !ui.dbgHistoryId ? buttons('保存调试记录', 'debug:save', 'small secondary', busy) : ''}
          ${terminal() ? buttons('导出快照', 'debug:export', 'small', busy) : ''}
          ${run.overrides?.originalRunId ? buttons('查看原算法运行', 'debug:original', 'small ghost', busy) : ''}</div></div>` : '';
      const toolbar = run ? `<div class="toolbar"><div class="toolbar-left">${esc(run.methodName)} v${esc(run.methodVersionNo)}
        <span class="smalltext muted">知识库 ${esc(run.knowledgeName)} v${esc(run.knowledgeVersionNo)}</span></div>
        ${btn('查看方法配置', 'debug:method', 'small ghost')}</div>` : methods.length ?
        `<div class="toolbar"><div class="toolbar-left"><label for="debug-method" class="smalltext bold">运行方法</label>
          <select id="debug-method" class="filter-select" data-filter="dbgMethod" ${busy ? 'disabled' : ''}>${opt(methods.map(item => [item.id,item.name]), method?.id)}</select>
          <select aria-label="方法版本" class="filter-select" data-filter="dbgVersion" ${busy ? 'disabled' : ''}>${opt(method?.versions.map(item => item.number) || [], version?.number)}</select>
          <span class="smalltext muted">知识库 ${esc(version?.knowledgeName)} ${esc(version?.knowledgeVersion)}</span></div>
          ${version ? btn('查看方法配置', 'debug:method', 'small ghost') : ''}</div>` : note(esc(methodsError || '暂无可运行的已保存方法。'));
      return title + toolbar + snapshot + `<div class="debug-area">${inputPanel()}<section class="panel result-overview">
        <div class="section-head"><strong class="section-title">识别结果</strong></div>${resultPanel()}</section></div>${process()}`;
    }

    function handle(action, value, methods) {
      switch (action) {
        case 'sample': if (!isLocked()) reset(sample); break;
        case 'clear': if (!isLocked()) reset(); break;
        case 'edit': if (run && terminal()) reset(run.input); break;
        case 'more': ui.dbgMore = !ui.dbgMore; redraw(); break;
        case 'reload': routeKey = ''; route(); break;
        case 'run': return runAction(async () => {
          if (run || ui.dbgRunId || ui.dbgHistoryId) return;
          const method = methods.find(item => item.id === ui.dbgMethodId);
          const version = method?.versions.find(item => item.number === ui.dbgVersion);
          if (!version) throw new Error('请选择已保存的方法版本');
          const next = await request('/runs', 'POST', {methodId: Number(method.id), methodVersionId: version.id, input: ui.dbgInput,
            ...(sourceBatchRunId ? {sourceBatchRunId} : {})});
          sourceBatchRunId = null;
          ++routeStamp;
          show(next);
        });
        case 'step': stage = value; detail = null; showCalls = false; redraw(); return loadStage();
        case 'calls': showCalls = !showCalls; redraw(); if (showCalls) return loadStage(); break;
        case 'method': return openMethod(String(run?.methodId || ui.dbgMethodId), run ? 'v' + run.methodVersionNo : ui.dbgVersion);
        case 'full': return branch('full', 'parse');
        case 'retry': return branch('retry', stage);
        case 'resume': return branch('resume', stage);
        case 'candidate': return runAction(async () => {
          choices = await request(`/runs/${run.id}/disciplines`);
          openModal('debugCandidate', {disciplineId: ''});
        });
        case 'applyCandidate':
          if (!ui.modalValues.disciplineId) return notify('请选择学科', true);
          return branch('add_candidate', 'judge', {disciplineId: Number(ui.modalValues.disciplineId)});
        case 'original': return runAction(async () => {
          const next = await request(`/runs/${run.overrides.originalRunId}`);
          ++routeStamp;
          show(next);
        });
        case 'history': return loadHistory(1);
        case 'searchHistory': return loadHistory(1, ui.modalValues.search);
        case 'historyPage': return loadHistory(Number(value));
        case 'openHistory': return runAction(async () => {
          const next = await request(`/history/${value}`);
          ++routeStamp;
          closeModal();
          show(next, true);
        });
        case 'save': return runAction(async () => {
          await request(`/runs/${run.id}/save`, 'POST');
          notify('已保存调试快照');
        });
        case 'export': return runAction(async () => {
          const snapshot = run;
          const trace = [];
          for (const [code] of stages) trace.push(await request(`/runs/${snapshot.id}/stages/${code}?calls=true`));
          download(`debug_${snapshot.id}.json`, JSON.stringify({...snapshot, stages: trace}, null, 2), 'application/json;charset=utf-8');
          notify('已导出调试快照');
        });
      }
    }
    return {page, handle, route, reset, historyView, candidateView, renderEvidence, editable: () => !isLocked()};
  }
});
})();

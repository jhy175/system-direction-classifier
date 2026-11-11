/* Hash routes work behind Spring Boot, including a configured context path. */
(() => {
  'use strict';
  const routes = Object.freeze({
    overview: [], methods: [], knowledge: [], batch: [], settings: [],
    methodDetail: ['methodId', 'methodVersion', 'methodView', 'methodTab', 'methodStage'],
    kbDetail: ['kbId', 'kbVersion', 'kbVersionMode', 'kbTab', 'kbSelected'],
    debug: ['dbgMethodId', 'dbgVersion', 'dbgRunId', 'dbgHistoryId'],
    batchWizard: [], taskDetail: ['batchTaskId']
  });
  const has = page => Object.prototype.hasOwnProperty.call(routes, page);
  const fragment = ui => {
    const page = has(ui.page) ? ui.page : 'overview';
    const query = new URLSearchParams();
    routes[page].forEach(name => { if (ui[name]) query.set(name, ui[name]); });
    return '#' + page + (query.size ? '?' + query.toString() : '');
  };
  window.ClassifierRouter = Object.freeze({
    has,
    read() {
      const [requested, query = ''] = window.location.hash.slice(1).split('?');
      const page = has(requested) ? requested : 'overview';
      const values = new URLSearchParams(query), params = {};
      routes[page].forEach(name => { if (values.has(name)) params[name] = values.get(name); });
      return {page, params};
    },
    write(ui, replace = false) {
      const hash = fragment(ui);
      if (window.location.hash !== hash) {
        window.history[replace ? 'replaceState' : 'pushState'](null, '', hash);
      }
    }
  });
})();

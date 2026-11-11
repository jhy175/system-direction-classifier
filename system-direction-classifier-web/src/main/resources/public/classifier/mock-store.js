/* Local records for the batch demonstration. */
(() => {
  'use strict';
  const key = 'direction-classifier.mock.v1';
  const serialize = ({tasks}) => JSON.stringify({tasks}, (name, item) =>
    /^(api_?key|key|password|authorization|access_?token|secret)$/i.test(name) ? undefined : item);
  window.ClassifierMockStore = Object.freeze({
    load(seed) {
      try {
        const value = JSON.parse(localStorage.getItem(key));
        if (Array.isArray(value?.tasks)) {
          const clean = rows => rows.filter(Boolean).map(({connections,knowledgeSnapshot,...row})=>row);
          const data = {tasks:clean(value.tasks)};
          if (Object.keys(value).some(name=>!(name in data)) ||
              value.tasks.some(row=>row && ('connections' in row || 'knowledgeSnapshot' in row))) {
            try { localStorage.setItem(key,serialize(data)); } catch (_) { /* Read-only storage. */ }
          }
          return data;
        }
      } catch (_) { /* Storage may be disabled or contain stale data. */ }
      return seed();
    },
    save(value) {
      try {
        localStorage.setItem(key,serialize(value));
        return true;
      } catch (_) { return false; }
    }
  });
})();

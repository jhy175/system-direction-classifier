/* Replace this local adapter when server persistence is introduced. */
(() => {
  'use strict';
  const key = 'direction-classifier.mock.v1';
  const collections = ['methods', 'connections', 'tasks', 'histories'];
  // Credential material must never be persisted or included in exported snapshots.
  const serialize = value => JSON.stringify(value, (name, item) =>
    /^(api_?key|key|password|authorization|access_?token|secret)$/i.test(name) ? undefined : item);
  window.ClassifierMockStore = Object.freeze({
    load(seed) {
      try {
        const value = JSON.parse(localStorage.getItem(key));
        if (value && collections.every(name => Array.isArray(value[name]))) {
          if (Array.isArray(value.kbs)) {
            const removed = new Set(value.kbs.map(row => row.id));
            delete value.kbs;
            for (const method of value.methods) {
              if (!removed.has(method.kbId)) continue;
              method.kbId = '';
              method.kbVersion = '';
              for (const version of [...method.versions, method.draft].filter(Boolean)) version.knowledgeVersion = '';
            }
            for (const row of [...value.tasks, ...value.histories]) delete row.knowledgeSnapshot;
            try { localStorage.setItem(key, serialize(value)); } catch (_) { /* Read-only storage. */ }
          }
          return value;
        }
      } catch (_) { /* Storage may be disabled or contain stale data. */ }
      return seed();
    },
    save(value) {
      try {
        localStorage.setItem(key, serialize(value));
        return true;
      } catch (_) { return false; }
    }
  });
})();

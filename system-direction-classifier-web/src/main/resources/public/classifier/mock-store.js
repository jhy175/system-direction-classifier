/* Replace this local adapter when server persistence is introduced. */
(() => {
  'use strict';
  const key = 'direction-classifier.mock.v1';
  const collections = ['methods', 'tasks', 'histories'];
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
          const modelIds = new Set(Array.isArray(value.connections) ? value.connections.map(row => row?.id).filter(Boolean) : []);
          let changed = 'connections' in value;
          delete value.connections;
          const stripModelIds = config => {
            for (const stage of Object.values(config || {})) {
              if (!stage || typeof stage !== 'object') continue;
              for (const field of ['model', 'embeddingModel']) {
                if (modelIds.has(stage[field]) || /^c\d+$/.test(stage[field] || '')) {
                  stage[field] = '';
                  changed = true;
                }
              }
            }
          };
          for (const method of value.methods) {
            for (const version of [...method.versions, method.draft, method.draftSaved]) stripModelIds(version?.config);
          }
          for (const row of [...value.tasks, ...value.histories]) {
            if ('connections' in row) {
              delete row.connections;
              changed = true;
            }
            stripModelIds(row.methodSnapshot?.config);
          }
          if (changed) {
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

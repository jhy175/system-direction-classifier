/* Replace this local adapter when server persistence is introduced. */
(() => {
  'use strict';
  const key = 'direction-classifier.mock.v1';
  const collections = ['methods', 'kbs', 'connections', 'tasks', 'histories'];
  // Credential material must never be persisted or included in exported snapshots.
  const serialize = value => JSON.stringify(value, (name, item) =>
    /^(api_?key|key|password|authorization|access_?token|secret)$/i.test(name) ? undefined : item);
  window.ClassifierMockStore = Object.freeze({
    load(seed) {
      try {
        const value = JSON.parse(localStorage.getItem(key));
        if (value && collections.every(name => Array.isArray(value[name]))) return value;
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

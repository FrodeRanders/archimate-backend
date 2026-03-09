import { writable } from 'svelte/store';

const KEY = 'collab-admin-selected-model';

const readStorage = () => {
  if (typeof localStorage === 'undefined') return '';
  try {
    return localStorage.getItem(KEY) || '';
  } catch {
    return '';
  }
};

const store = writable('');

export const selectedModelId = {
  subscribe: store.subscribe,
  set(value) {
    const next = value ?? '';
    store.set(next);
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem(KEY, String(next));
      } catch {
        // Ignore storage failures.
      }
    }
  },
  init() {
    store.set(readStorage());
  }
};

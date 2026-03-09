import { writable } from 'svelte/store';

const TOKEN_KEY = 'collab-admin-auth-token';
const USER_KEY = 'collab-admin-auth-user';
const ROLES_KEY = 'collab-admin-auth-roles';
const POLL_KEY = 'collab-admin-poll-seconds';

const readStorage = (key, fallback = '') => {
  if (typeof localStorage === 'undefined') return fallback;
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
};

const createPersisted = (key, fallback = '') => {
  const store = writable(fallback);
  return {
    subscribe: store.subscribe,
    set(value) {
      const next = value ?? '';
      store.set(next);
      if (typeof localStorage !== 'undefined') {
        try {
          localStorage.setItem(key, String(next));
        } catch {
          // Ignore storage failures in private browsing or restricted contexts.
        }
      }
    },
    init() {
      store.set(readStorage(key, fallback));
    }
  };
};

export const authToken = createPersisted(TOKEN_KEY);
export const authUser = createPersisted(USER_KEY);
export const authRoles = createPersisted(ROLES_KEY);
export const pollSeconds = createPersisted(POLL_KEY, '4');

export const authSummary = writable('Run Auth Check to resolve the current request identity.');
export const authDiagnostics = writable(null);

export const initAuthStores = () => {
  authToken.init();
  authUser.init();
  authRoles.init();
  pollSeconds.init();
};

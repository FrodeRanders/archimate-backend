import { w as writable } from "./index.js";
const TOKEN_KEY = "collab-admin-auth-token";
const USER_KEY = "collab-admin-auth-user";
const ROLES_KEY = "collab-admin-auth-roles";
const POLL_KEY = "collab-admin-poll-seconds";
const readStorage = (key, fallback = "") => {
  if (typeof localStorage === "undefined") return fallback;
  try {
    return localStorage.getItem(key) || fallback;
  } catch {
    return fallback;
  }
};
const createPersisted = (key, fallback = "") => {
  const store = writable(fallback);
  return {
    subscribe: store.subscribe,
    set(value) {
      const next = value ?? "";
      store.set(next);
      if (typeof localStorage !== "undefined") {
        try {
          localStorage.setItem(key, String(next));
        } catch {
        }
      }
    },
    init() {
      store.set(readStorage(key, fallback));
    }
  };
};
const authToken = createPersisted(TOKEN_KEY);
const authUser = createPersisted(USER_KEY);
const authRoles = createPersisted(ROLES_KEY);
const pollSeconds = createPersisted(POLL_KEY, "4");
const authSummary = writable("Run Auth Check to resolve the current request identity.");
export {
  authToken as a,
  authUser as b,
  authRoles as c,
  authSummary as d,
  pollSeconds as p
};

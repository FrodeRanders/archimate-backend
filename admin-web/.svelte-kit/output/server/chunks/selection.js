import { w as writable } from "./index.js";
const KEY = "collab-admin-selected-model";
const readStorage = () => {
  if (typeof localStorage === "undefined") return "";
  try {
    return localStorage.getItem(KEY) || "";
  } catch {
    return "";
  }
};
const store = writable("");
const selectedModelId = {
  subscribe: store.subscribe,
  set(value) {
    const next = value ?? "";
    store.set(next);
    if (typeof localStorage !== "undefined") {
      try {
        localStorage.setItem(KEY, String(next));
      } catch {
      }
    }
  },
  init() {
    store.set(readStorage());
  }
};
export {
  selectedModelId as s
};

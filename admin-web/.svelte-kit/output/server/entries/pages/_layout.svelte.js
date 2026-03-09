import { g as getContext, s as store_get, e as escape_html, a as ensure_array_like, b as attr, c as attr_class, u as unsubscribe_stores, d as slot } from "../../chunks/index2.js";
import "clsx";
import "@sveltejs/kit/internal";
import "../../chunks/exports.js";
import "../../chunks/utils.js";
import "@sveltejs/kit/internal/server";
import "../../chunks/root.js";
import "../../chunks/state.svelte.js";
import "../../chunks/auth.js";
import "../../chunks/selection.js";
const getStores = () => {
  const stores$1 = getContext("__svelte__");
  return {
    /** @type {typeof page} */
    page: {
      subscribe: stores$1.page.subscribe
    },
    /** @type {typeof navigating} */
    navigating: {
      subscribe: stores$1.navigating.subscribe
    },
    /** @type {typeof updated} */
    updated: stores$1.updated
  };
};
const page = {
  subscribe(fn) {
    const store = getStores().page;
    return store.subscribe(fn);
  }
};
function Header($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    var $$store_subs;
    let currentPath, context;
    const tabs = [
      { label: "Overview", href: "/admin-ui/" },
      { label: "Models", href: "/admin-ui/models" },
      { label: "Versions", href: "/admin-ui/versions" },
      { label: "Access", href: "/admin-ui/access" },
      { label: "Sessions", href: "/admin-ui/sessions" },
      { label: "Audit", href: "/admin-ui/audit" }
    ];
    const contextByPath = {
      "/admin-ui/": "Server overview and model focus",
      "/admin-ui/models": "Model lifecycle and maintenance",
      "/admin-ui/versions": "Tags, export, and import",
      "/admin-ui/access": "Identity, ACLs, and diagnostics",
      "/admin-ui/sessions": "Live collaboration sessions",
      "/admin-ui/audit": "Admin and websocket audit trails"
    };
    currentPath = store_get($$store_subs ??= {}, "$page", page).url.pathname;
    context = contextByPath[currentPath] || "Admin console";
    $$renderer2.push(`<header class="header svelte-1elxaub"><div class="brand svelte-1elxaub"><div class="mark svelte-1elxaub">AC</div> <div class="copy"><div class="title svelte-1elxaub">Collab Admin</div> <div class="subtitle svelte-1elxaub">${escape_html(context)}</div></div></div> <nav class="tabs svelte-1elxaub" aria-label="Admin sections"><!--[-->`);
    const each_array = ensure_array_like(tabs);
    for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
      let tab = each_array[$$index];
      $$renderer2.push(`<a${attr("href", tab.href)}${attr_class("svelte-1elxaub", void 0, { "active": currentPath === tab.href })}>${escape_html(tab.label)}</a>`);
    }
    $$renderer2.push(`<!--]--></nav></header>`);
    if ($$store_subs) unsubscribe_stores($$store_subs);
  });
}
function _layout($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    Header($$renderer2);
    $$renderer2.push(`<!----> <main class="page svelte-12qhfyh"><!--[-->`);
    slot($$renderer2, $$props, "default", {});
    $$renderer2.push(`<!--]--></main>`);
  });
}
export {
  _layout as default
};

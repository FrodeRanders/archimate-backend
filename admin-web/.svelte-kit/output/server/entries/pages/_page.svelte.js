import { s as store_get, b as attr, e as escape_html, u as unsubscribe_stores, a as ensure_array_like, c as attr_class } from "../../chunks/index2.js";
import { P as Panel } from "../../chunks/Panel.js";
import { S as StatusPill } from "../../chunks/StatusPill.js";
import { a as authToken, b as authUser, c as authRoles, p as pollSeconds, d as authSummary } from "../../chunks/auth.js";
import { w as writable } from "../../chunks/index.js";
import "clsx";
import { d as describeTokenStatus, a as describeTokenIdentity } from "../../chunks/auth2.js";
const selectedModelId = writable("");
const safe = (value) => value === void 0 || value === null ? "" : String(value);
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    var $$store_subs;
    let tokenStatus, tokenIdentity;
    let overview = [];
    let status = "Loading overview...";
    let loading = false;
    let limit = 25;
    let accessFilter = "all";
    tokenStatus = describeTokenStatus(store_get($$store_subs ??= {}, "$authToken", authToken));
    tokenIdentity = describeTokenIdentity(store_get($$store_subs ??= {}, "$authToken", authToken));
    $$renderer2.push(`<div class="hero svelte-1uha8ag"><div><div class="eyebrow svelte-1uha8ag">Overview</div> <h1 class="svelte-1uha8ag">Server state, identity context, and selected model focus.</h1> <p class="svelte-1uha8ag">The first migration slice keeps the high-value operator loop in one place while the old monolith remains available for the deeper workflows.</p></div> <div class="hero-actions svelte-1uha8ag"><button>Auth Check</button> <button${attr("disabled", loading, true)}>${escape_html("Refresh")}</button></div></div> <div class="top-grid svelte-1uha8ag">`);
    Panel($$renderer2, {
      title: "Auth Inputs",
      subtitle: "Shared inputs for bootstrap and bearer-token flows.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="field-grid svelte-1uha8ag"><label class="svelte-1uha8ag"><span>Bearer Token</span> <textarea rows="4" placeholder="JWT token for oidc mode">`);
        const $$body = escape_html(store_get($$store_subs ??= {}, "$authToken", authToken));
        if ($$body) {
          $$renderer3.push(`${$$body}`);
        }
        $$renderer3.push(`</textarea></label> <label class="svelte-1uha8ag"><span>Bootstrap User</span> <input${attr("value", store_get($$store_subs ??= {}, "$authUser", authUser))} placeholder="admin-user"/></label> <label class="svelte-1uha8ag"><span>Bootstrap Roles</span> <input${attr("value", store_get($$store_subs ??= {}, "$authRoles", authRoles))} placeholder="admin,model_writer"/></label> <label class="svelte-1uha8ag"><span>Poll Seconds</span> <input type="number" min="0" max="300"${attr("value", store_get($$store_subs ??= {}, "$pollSeconds", pollSeconds))}/></label> <label class="svelte-1uha8ag"><span>Overview Limit</span> <input type="number" min="1" max="200"${attr("value", limit)}/></label> <label class="svelte-1uha8ag"><span>Access Filter</span> `);
        $$renderer3.select({ value: accessFilter }, ($$renderer4) => {
          $$renderer4.option({ value: "all" }, ($$renderer5) => {
            $$renderer5.push(`all`);
          });
          $$renderer4.option({ value: "open" }, ($$renderer5) => {
            $$renderer5.push(`open`);
          });
          $$renderer4.option({ value: "acl" }, ($$renderer5) => {
            $$renderer5.push(`acl`);
          });
        });
        $$renderer3.push(`</label></div> <div class="hint-grid svelte-1uha8ag"><div class="svelte-1uha8ag"><strong class="svelte-1uha8ag">Token status</strong><span>${escape_html(tokenStatus)}</span></div> <div class="svelte-1uha8ag"><strong class="svelte-1uha8ag">Token preview</strong><span>${escape_html(tokenIdentity)}</span></div> <div class="svelte-1uha8ag"><strong class="svelte-1uha8ag">Resolved identity</strong><span>${escape_html(store_get($$store_subs ??= {}, "$authSummary", authSummary))}</span></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Selected Model",
      subtitle: "Focused window summary for the active row.",
      children: ($$renderer3) => {
        {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<p class="empty svelte-1uha8ag">Select a model to see its focused status.</p>`);
        }
        $$renderer3.push(`<!--]-->`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> `);
    Panel($$renderer2, {
      title: "Known Models",
      subtitle: "Model list migrated out of the monolithic admin page.",
      children: ($$renderer3) => {
        $$renderer3.push(`<table><thead><tr><th>Model</th><th>Name</th><th>Access</th><th>Sessions</th><th>Tags</th><th>Latest Tag</th><th>Snapshot Head</th><th>Persisted</th><th>Commit</th></tr></thead><tbody>`);
        if (overview.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<tr><td colspan="9" class="empty svelte-1uha8ag">No models match the current filter.</td></tr>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array = ensure_array_like(overview);
          for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
            let row = each_array[$$index];
            $$renderer3.push(`<tr${attr_class("svelte-1uha8ag", void 0, {
              "selected": row.modelId === store_get($$store_subs ??= {}, "$selectedModelId", selectedModelId)
            })}><td><button class="row-button svelte-1uha8ag">${escape_html(row.modelId)}</button></td><td>${escape_html(row.modelName || "")}</td><td>`);
            if (row.accessSummary?.aclConfigured) {
              $$renderer3.push("<!--[0-->");
              StatusPill($$renderer3, { mode: "acl", label: "acl" });
            } else {
              $$renderer3.push("<!--[-1-->");
              StatusPill($$renderer3, { mode: "open", label: "open" });
            }
            $$renderer3.push(`<!--]--></td><td>${escape_html(safe(row.activeSessionCount || 0))}</td><td>${escape_html(safe(row.tagSummary?.tagCount || 0))}</td><td>${escape_html(row.tagSummary?.latestTagName || "none")}</td><td>${escape_html(safe(row.snapshotHeadRevision))}</td><td>${escape_html(safe(row.persistedHeadRevision))}</td><td>${escape_html(safe(row.lastCommitRevision))}</td></tr>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></tbody></table>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> <div class="footer-status svelte-1uha8ag">${escape_html(status)}</div>`);
    if ($$store_subs) unsubscribe_stores($$store_subs);
  });
}
export {
  _page as default
};

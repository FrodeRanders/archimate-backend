import { s as store_get, e as escape_html, u as unsubscribe_stores, b as attr, a as ensure_array_like, c as attr_class } from "../../../chunks/index2.js";
import { P as Panel } from "../../../chunks/Panel.js";
import { S as StatusPill } from "../../../chunks/StatusPill.js";
import { a as authToken, d as authSummary, b as authUser, c as authRoles } from "../../../chunks/auth.js";
import { d as describeTokenStatus, a as describeTokenIdentity } from "../../../chunks/auth2.js";
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    var $$store_subs;
    let tokenStatus, tokenIdentity;
    let overview = [];
    let selectedModelId = "";
    let pageStatus = "Loading access controls...";
    let adminUsers = "";
    let writerUsers = "";
    let readerUsers = "";
    tokenStatus = describeTokenStatus(store_get($$store_subs ??= {}, "$authToken", authToken));
    tokenIdentity = describeTokenIdentity(store_get($$store_subs ??= {}, "$authToken", authToken));
    $$renderer2.push(`<div class="hero svelte-a23kkf"><div><div class="eyebrow svelte-a23kkf">Access</div> <h1 class="svelte-a23kkf">Identity diagnostics and model ACLs.</h1> <p class="svelte-a23kkf">This route isolates access management from the overview workflow and makes the auth surface explicit.</p></div> <div class="actions svelte-a23kkf"><button>Auth Check</button> <button>Refresh</button></div></div> <div class="grid svelte-a23kkf">`);
    Panel($$renderer2, {
      title: "Current Identity",
      subtitle: "Resolved request context and local token guidance.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-a23kkf"><div class="line svelte-a23kkf"><strong>Resolved identity</strong><span>${escape_html(store_get($$store_subs ??= {}, "$authSummary", authSummary))}</span></div> <div class="line svelte-a23kkf"><strong>Token status</strong><span>${escape_html(tokenStatus)}</span></div> <div class="line svelte-a23kkf"><strong>Token preview</strong><span>${escape_html(tokenIdentity)}</span></div> `);
        {
          $$renderer3.push("<!--[-1-->");
        }
        $$renderer3.push(`<!--]--></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Input Mode",
      subtitle: "Use bootstrap inputs for local authz testing or a bearer token for oidc mode.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="field-grid svelte-a23kkf"><label class="svelte-a23kkf"><span>Bearer Token</span> <textarea rows="4" placeholder="JWT token for oidc mode">`);
        const $$body = escape_html(store_get($$store_subs ??= {}, "$authToken", authToken));
        if ($$body) {
          $$renderer3.push(`${$$body}`);
        }
        $$renderer3.push(`</textarea></label> <label class="svelte-a23kkf"><span>Bootstrap User</span> <input${attr("value", store_get($$store_subs ??= {}, "$authUser", authUser))} placeholder="admin-user"/></label> <label class="svelte-a23kkf"><span>Bootstrap Roles</span> <input${attr("value", store_get($$store_subs ??= {}, "$authRoles", authRoles))} placeholder="admin,model_writer"/></label></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="grid svelte-a23kkf">`);
    Panel($$renderer2, {
      title: "Models",
      subtitle: "Choose the model whose ACL you want to inspect or edit.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="table-wrap svelte-a23kkf"><table><thead><tr><th>Selected</th><th>Model</th><th>Name</th><th>Access</th><th>Admins</th><th>Writers</th><th>Readers</th></tr></thead><tbody>`);
        if (overview.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<tr><td colspan="7" class="empty svelte-a23kkf">No models available.</td></tr>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array = ensure_array_like(overview);
          for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
            let row = each_array[$$index];
            $$renderer3.push(`<tr${attr_class("svelte-a23kkf", void 0, { "selected": row.modelId === selectedModelId })}><td><input type="radio" name="model"${attr("value", row.modelId)}${attr("checked", row.modelId === selectedModelId, true)}/></td><td>${escape_html(row.modelId)}</td><td>${escape_html(row.modelName || "")}</td><td>`);
            if (row.accessSummary?.aclConfigured) {
              $$renderer3.push("<!--[0-->");
              StatusPill($$renderer3, { mode: "acl", label: "acl" });
            } else {
              $$renderer3.push("<!--[-1-->");
              StatusPill($$renderer3, { mode: "open", label: "open" });
            }
            $$renderer3.push(`<!--]--></td><td>${escape_html(row.accessSummary?.adminUserCount || 0)}</td><td>${escape_html(row.accessSummary?.writerUserCount || 0)}</td><td>${escape_html(row.accessSummary?.readerUserCount || 0)}</td></tr>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></tbody></table></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "ACL Editor",
      subtitle: "One user id per line or comma-separated. Saving here updates the server-side model ACL.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-a23kkf"><div class="line svelte-a23kkf"><strong>Selected model</strong> <span>${escape_html("none")}</span></div> <div class="line svelte-a23kkf"><strong>Access mode</strong> <span>`);
        {
          $$renderer3.push("<!--[-1-->");
          StatusPill($$renderer3, { mode: "open", label: "open" });
        }
        $$renderer3.push(`<!--]--></span></div> <div class="field-grid single svelte-a23kkf"><label class="svelte-a23kkf"><span>Model admins</span> <textarea rows="5" placeholder="owner-user backup-admin">`);
        const $$body_1 = escape_html(adminUsers);
        if ($$body_1) {
          $$renderer3.push(`${$$body_1}`);
        }
        $$renderer3.push(`</textarea></label> <label class="svelte-a23kkf"><span>Writers</span> <textarea rows="5" placeholder="editor-a editor-b">`);
        const $$body_2 = escape_html(writerUsers);
        if ($$body_2) {
          $$renderer3.push(`${$$body_2}`);
        }
        $$renderer3.push(`</textarea></label> <label class="svelte-a23kkf"><span>Readers</span> <textarea rows="5" placeholder="viewer-a viewer-b">`);
        const $$body_3 = escape_html(readerUsers);
        if ($$body_3) {
          $$renderer3.push(`${$$body_3}`);
        }
        $$renderer3.push(`</textarea></label></div> <div class="actions svelte-a23kkf"><button>Save ACL</button></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="footer-status svelte-a23kkf">${escape_html(pageStatus)}</div>`);
    if ($$store_subs) unsubscribe_stores($$store_subs);
  });
}
export {
  _page as default
};

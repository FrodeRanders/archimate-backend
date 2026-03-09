import { e as escape_html, a as ensure_array_like, c as attr_class, b as attr } from "../../../chunks/index2.js";
import { P as Panel } from "../../../chunks/Panel.js";
/* empty css                                                       */
import "../../../chunks/auth.js";
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    let overview = [];
    let selectedModelId = "";
    let pageStatus = "Loading sessions...";
    $$renderer2.push(`<div class="hero svelte-98wg7q"><div><div class="eyebrow svelte-98wg7q">Sessions</div> <h1 class="svelte-98wg7q">Live websocket session diagnostics.</h1> <p class="svelte-98wg7q">This route isolates the operator view of joined sessions, including read-only tag sessions and current writability.</p></div> <div class="actions svelte-98wg7q"><button>Refresh</button> <button>Copy Sessions</button></div></div> <div class="grid svelte-98wg7q">`);
    Panel($$renderer2, {
      title: "Models",
      subtitle: "Choose the model whose live collaboration sessions you want to inspect.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="table-wrap svelte-98wg7q"><table><thead><tr><th>Selected</th><th>Model</th><th>Name</th><th>Sessions</th><th>Tags</th></tr></thead><tbody>`);
        if (overview.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<tr><td colspan="5" class="empty svelte-98wg7q">No models available.</td></tr>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array = ensure_array_like(overview);
          for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
            let row = each_array[$$index];
            $$renderer3.push(`<tr${attr_class("svelte-98wg7q", void 0, { "selected": row.modelId === selectedModelId })}><td><input type="radio" name="session-model"${attr("value", row.modelId)}${attr("checked", row.modelId === selectedModelId, true)}/></td><td>${escape_html(row.modelId)}</td><td>${escape_html(row.modelName || "")}</td><td>${escape_html(row.activeSessionCount || 0)}</td><td>${escape_html(row.tagSummary?.tagCount || 0)}</td></tr>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></tbody></table></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Session Summary",
      subtitle: "Current session counts for the selected model.",
      children: ($$renderer3) => {
        {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<div class="empty svelte-98wg7q">Select a model to see session diagnostics.</div>`);
        }
        $$renderer3.push(`<!--]-->`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> `);
    Panel($$renderer2, {
      title: "Active Sessions",
      subtitle: "Resolved user, normalized roles, joined ref, and writability.",
      children: ($$renderer3) => {
        {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<div class="empty svelte-98wg7q">No active joined sessions for the selected model.</div>`);
        }
        $$renderer3.push(`<!--]-->`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> <div class="footer-status svelte-98wg7q">${escape_html(pageStatus)}</div>`);
  });
}
export {
  _page as default
};

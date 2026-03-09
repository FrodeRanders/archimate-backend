import { e as escape_html, b as attr, a as ensure_array_like, c as attr_class } from "../../../chunks/index2.js";
import { P as Panel } from "../../../chunks/Panel.js";
import { S as StatusPill } from "../../../chunks/StatusPill.js";
import "../../../chunks/auth.js";
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    let overview = [];
    let selectedModelId = "";
    let pageStatus = "Loading models...";
    let newModelId = "";
    let newModelName = "";
    let renameValue = "";
    let compactRetain = "0";
    let deleteForce = false;
    $$renderer2.push(`<div class="hero svelte-18pldtr"><div><div class="eyebrow svelte-18pldtr">Models</div> <h1 class="svelte-18pldtr">Model lifecycle and maintenance.</h1> <p class="svelte-18pldtr">This route holds the administrative actions that change or repair model state, separate from access and version workflows.</p></div> <div class="actions svelte-18pldtr"><button>Refresh</button> <button>Rebuild</button></div></div> <div class="grid svelte-18pldtr">`);
    Panel($$renderer2, {
      title: "Create Model",
      subtitle: "Register a new model before clients join it.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-18pldtr"><label class="svelte-18pldtr"><span>Model ID</span> <input${attr("value", newModelId)} placeholder="demo-model"/></label> <label class="svelte-18pldtr"><span>Model Name</span> <input${attr("value", newModelName)} placeholder="Demo Model"/></label> <div class="actions svelte-18pldtr"><button>Create Model</button></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Selected Model",
      subtitle: "Current lifecycle actions for the active model.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-18pldtr"><div class="line svelte-18pldtr"><strong>Model</strong><span>${escape_html("none")}</span></div> <label class="svelte-18pldtr"><span>Rename</span> <input${attr("value", renameValue)} placeholder="Selected model name"/></label> <label class="svelte-18pldtr"><span>Compact retain revisions</span> <input type="number" min="0"${attr("value", compactRetain)}/></label> <label class="checkbox svelte-18pldtr"><input type="checkbox"${attr("checked", deleteForce, true)}/> <span>Force delete even when sessions are active</span></label> <div class="actions svelte-18pldtr"><button>Rename</button> <button>Rebuild</button> <button>Compact</button> <button class="danger svelte-18pldtr">Delete</button></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="grid svelte-18pldtr">`);
    Panel($$renderer2, {
      title: "Models",
      subtitle: "Choose the model to inspect or administer.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="table-wrap svelte-18pldtr"><table><thead><tr><th>Selected</th><th>Model</th><th>Name</th><th>Access</th><th>Sessions</th><th>Tags</th></tr></thead><tbody>`);
        if (overview.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<tr><td colspan="6" class="empty svelte-18pldtr">No models available.</td></tr>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array = ensure_array_like(overview);
          for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
            let row = each_array[$$index];
            $$renderer3.push(`<tr${attr_class("svelte-18pldtr", void 0, { "selected": row.modelId === selectedModelId })}><td><input type="radio" name="model"${attr("value", row.modelId)}${attr("checked", row.modelId === selectedModelId, true)}/></td><td>${escape_html(row.modelId)}</td><td>${escape_html(row.modelName || "")}</td><td>`);
            if (row.accessSummary?.aclConfigured) {
              $$renderer3.push("<!--[0-->");
              StatusPill($$renderer3, { mode: "acl", label: "acl" });
            } else {
              $$renderer3.push("<!--[-1-->");
              StatusPill($$renderer3, { mode: "open", label: "open" });
            }
            $$renderer3.push(`<!--]--></td><td>${escape_html(row.activeSessionCount || 0)}</td><td>${escape_html(row.tagSummary?.tagCount || 0)}</td></tr>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></tbody></table></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Focused Status",
      subtitle: "Operational summary for the selected model.",
      children: ($$renderer3) => {
        {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<div class="empty svelte-18pldtr">Select a model to see focused status.</div>`);
        }
        $$renderer3.push(`<!--]-->`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="footer-status svelte-18pldtr">${escape_html(pageStatus)}</div>`);
  });
}
export {
  _page as default
};

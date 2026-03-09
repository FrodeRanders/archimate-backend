import { e as escape_html, a as ensure_array_like, c as attr_class, b as attr } from "../../../chunks/index2.js";
import { P as PageHero, a as Panel } from "../../../chunks/PageHero.js";
import "../../../chunks/auth.js";
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    let overview = [];
    let selectedModelId = "";
    let pageStatus = "Loading versions...";
    let tags = [];
    let exportJson = "";
    let importJson = "";
    let overwrite = false;
    let newTagName = "";
    let newTagDescription = "";
    PageHero($$renderer2, {
      eyebrow: "Versions",
      title: "Immutable tags, export, and import.",
      description: "Keep the linear versioning workflow separate from day-to-day model operations.",
      children: ($$renderer3) => {
        $$renderer3.push(`<button>Refresh</button> <button>Export Selected</button>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> <div class="grid svelte-g2d532">`);
    Panel($$renderer2, {
      title: "Models",
      subtitle: "Pick the model whose tags or package you want to manage.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="table-wrap svelte-g2d532"><table><thead><tr><th>Selected</th><th>Model</th><th>Name</th><th>Tags</th><th>Latest Tag</th></tr></thead><tbody>`);
        if (overview.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<tr><td colspan="5" class="empty svelte-g2d532">No models available.</td></tr>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array = ensure_array_like(overview);
          for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
            let row = each_array[$$index];
            $$renderer3.push(`<tr${attr_class("svelte-g2d532", void 0, { "selected": row.modelId === selectedModelId })}><td><input type="radio" name="version-model"${attr("value", row.modelId)}${attr("checked", row.modelId === selectedModelId, true)}/></td><td>${escape_html(row.modelId)}</td><td>${escape_html(row.modelName || "")}</td><td>${escape_html(row.tagSummary?.tagCount || 0)}</td><td>${escape_html(row.tagSummary?.latestTagName || "none")}</td></tr>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></tbody></table></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Create Tag",
      subtitle: "Tags are immutable and captured from the current HEAD state.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-g2d532"><label class="svelte-g2d532"><span>Tag name</span> <input${attr("value", newTagName)} placeholder="v1.2"/></label> <label class="svelte-g2d532"><span>Description</span> <input${attr("value", newTagDescription)} placeholder="approved-2026-03-09"/></label> <div class="actions svelte-g2d532"><button>Create Tag From HEAD</button></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="grid svelte-g2d532">`);
    Panel($$renderer2, {
      title: "Tags",
      subtitle: "Historical pull points for the selected model.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-g2d532">`);
        if (tags.length === 0) {
          $$renderer3.push("<!--[0-->");
          $$renderer3.push(`<div class="empty svelte-g2d532">No tags for the selected model.</div>`);
        } else {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<!--[-->`);
          const each_array_1 = ensure_array_like(tags);
          for (let $$index_1 = 0, $$length = each_array_1.length; $$index_1 < $$length; $$index_1++) {
            let tag = each_array_1[$$index_1];
            $$renderer3.push(`<div class="tag-card svelte-g2d532"><div class="tag-top svelte-g2d532"><strong>${escape_html(tag.tagName)}</strong> <button>Delete</button></div> <div class="tag-meta svelte-g2d532"><span>revision ${escape_html(tag.revision)}</span> <span>${escape_html(tag.createdAt || "n/a")}</span></div> <div class="tag-description svelte-g2d532">${escape_html(tag.description || "No description.")}</div></div>`);
          }
          $$renderer3.push(`<!--]-->`);
        }
        $$renderer3.push(`<!--]--></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Import / Export",
      subtitle: "Exports preserve metadata, op-log history, snapshot state, and tags.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-g2d532"><label class="svelte-g2d532"><span>Export JSON</span> <textarea rows="10" placeholder="Exported package appears here after Export Selected">`);
        const $$body = escape_html(exportJson);
        if ($$body) {
          $$renderer3.push(`${$$body}`);
        }
        $$renderer3.push(`</textarea></label> <label class="svelte-g2d532"><span>Import JSON</span> <textarea rows="10" placeholder="Paste a package to import">`);
        const $$body_1 = escape_html(importJson);
        if ($$body_1) {
          $$renderer3.push(`${$$body_1}`);
        }
        $$renderer3.push(`</textarea></label> <label class="checkbox svelte-g2d532"><input type="checkbox"${attr("checked", overwrite, true)}/> <span>Overwrite existing model when import conflicts</span></label> <div class="actions svelte-g2d532"><button>Import Package</button></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="footer-status svelte-g2d532">${escape_html(pageStatus)}</div>`);
  });
}
export {
  _page as default
};

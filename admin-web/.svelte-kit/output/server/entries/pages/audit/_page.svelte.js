import { e as escape_html } from "../../../chunks/index2.js";
import "clsx";
import { P as Panel } from "../../../chunks/Panel.js";
import "../../../chunks/auth.js";
function _page($$renderer, $$props) {
  $$renderer.component(($$renderer2) => {
    let pageStatus = "Loading audit configuration...";
    $$renderer2.push(`<div class="hero svelte-1qyu7y8"><div><div class="eyebrow svelte-1qyu7y8">Audit</div> <h1 class="svelte-1qyu7y8">Structured audit configuration and operator guidance.</h1> <p class="svelte-1qyu7y8">This route makes the active audit settings visible without forcing operators back into README files or server configs.</p></div> <div class="actions svelte-1qyu7y8"><button>Refresh</button> <button>Copy Config</button></div></div> <div class="grid svelte-1qyu7y8">`);
    Panel($$renderer2, {
      title: "Current Config",
      subtitle: "Effective audit-related runtime values exposed by the server.",
      children: ($$renderer3) => {
        {
          $$renderer3.push("<!--[-1-->");
          $$renderer3.push(`<div class="empty svelte-1qyu7y8">Audit configuration is not available yet.</div>`);
        }
        $$renderer3.push(`<!--]-->`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----> `);
    Panel($$renderer2, {
      title: "Operator Guidance",
      subtitle: "What to pay attention to when audit is enabled.",
      children: ($$renderer3) => {
        $$renderer3.push(`<div class="stack svelte-1qyu7y8"><div class="guide svelte-1qyu7y8"><strong class="svelte-1qyu7y8">\`admin_audit\`</strong> <span class="svelte-1qyu7y8">Machine-readable JSON for diagnostics access and mutating admin actions. Use a dedicated sink or index if operators depend on it.</span></div> <div class="guide svelte-1qyu7y8"><strong class="svelte-1qyu7y8">\`ws_audit\`</strong> <span class="svelte-1qyu7y8">Lifecycle-oriented websocket trail. Keep \`app.audit.websocket.actions\` narrow unless you explicitly need broad session visibility.</span></div> <div class="guide svelte-1qyu7y8"><strong class="svelte-1qyu7y8">Verbose mode</strong> <span class="svelte-1qyu7y8">\`app.audit.websocket.verbose=true\` also emits accepted websocket messages and increases volume noticeably.</span></div> <div class="guide svelte-1qyu7y8"><strong class="svelte-1qyu7y8">Retention</strong> <span class="svelte-1qyu7y8">Keep audit retention shorter than ordinary application logs if the admin dashboard refresh interval is low.</span></div></div>`);
      },
      $$slots: { default: true }
    });
    $$renderer2.push(`<!----></div> <div class="footer-status svelte-1qyu7y8">${escape_html(pageStatus)}</div>`);
  });
}
export {
  _page as default
};

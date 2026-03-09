import { ab as fallback, e as escape_html, d as slot, ac as bind_props } from "./index2.js";
function Panel($$renderer, $$props) {
  let title = fallback($$props["title"], "");
  let subtitle = fallback($$props["subtitle"], "");
  $$renderer.push(`<section class="panel svelte-hxsa5u"><header class="panel-header svelte-hxsa5u"><div><h2 class="svelte-hxsa5u">${escape_html(title)}</h2> `);
  if (subtitle) {
    $$renderer.push("<!--[0-->");
    $$renderer.push(`<p class="svelte-hxsa5u">${escape_html(subtitle)}</p>`);
  } else {
    $$renderer.push("<!--[-1-->");
  }
  $$renderer.push(`<!--]--></div> <!--[-->`);
  slot($$renderer, $$props, "actions", {});
  $$renderer.push(`<!--]--></header> <div class="panel-body svelte-hxsa5u"><!--[-->`);
  slot($$renderer, $$props, "default", {});
  $$renderer.push(`<!--]--></div></section>`);
  bind_props($$props, { title, subtitle });
}
export {
  Panel as P
};

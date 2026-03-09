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
function PageHero($$renderer, $$props) {
  let eyebrow = fallback($$props["eyebrow"], "");
  let title = fallback($$props["title"], "");
  let description = fallback($$props["description"], "");
  $$renderer.push(`<section class="hero svelte-4s1vdh"><div>`);
  if (eyebrow) {
    $$renderer.push("<!--[0-->");
    $$renderer.push(`<div class="eyebrow svelte-4s1vdh">${escape_html(eyebrow)}</div>`);
  } else {
    $$renderer.push("<!--[-1-->");
  }
  $$renderer.push(`<!--]--> <h1 class="svelte-4s1vdh">${escape_html(title)}</h1> `);
  if (description) {
    $$renderer.push("<!--[0-->");
    $$renderer.push(`<p class="svelte-4s1vdh">${escape_html(description)}</p>`);
  } else {
    $$renderer.push("<!--[-1-->");
  }
  $$renderer.push(`<!--]--></div> <div class="actions svelte-4s1vdh"><!--[-->`);
  slot($$renderer, $$props, "default", {});
  $$renderer.push(`<!--]--></div></section>`);
  bind_props($$props, { eyebrow, title, description });
}
export {
  PageHero as P,
  Panel as a
};

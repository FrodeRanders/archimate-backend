import { ab as fallback, c as attr_class, e as escape_html, ac as bind_props } from "./index2.js";
/* empty css                                         */
function StatusPill($$renderer, $$props) {
  let mode = fallback($$props["mode"], "open");
  let label = fallback($$props["label"], "");
  $$renderer.push(`<span${attr_class("pill svelte-1swmi23", void 0, { "open": mode === "open", "acl": mode === "acl" })}>${escape_html(label || mode)}</span>`);
  bind_props($$props, { mode, label });
}
export {
  StatusPill as S
};

import * as universal from '../entries/pages/_layout.js';

export const index = 0;
let component_cache;
export const component = async () => component_cache ??= (await import('../entries/pages/_layout.svelte.js')).default;
export { universal };
export const universal_id = "src/routes/+layout.js";
export const imports = ["_app/immutable/nodes/0.wqlvAuwI.js","_app/immutable/chunks/BxAtPitE.js","_app/immutable/chunks/Cgcn8Wh1.js","_app/immutable/chunks/coiopDDu.js","_app/immutable/chunks/By2_c10o.js","_app/immutable/chunks/Db6kSpfE.js","_app/immutable/chunks/BRXuW3nO.js","_app/immutable/chunks/Cplp6hmO.js"];
export const stylesheets = ["_app/immutable/assets/0.BYexU6Qm.css"];
export const fonts = [];

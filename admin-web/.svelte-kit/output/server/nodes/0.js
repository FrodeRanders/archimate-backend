import * as universal from '../entries/pages/_layout.js';

export const index = 0;
let component_cache;
export const component = async () => component_cache ??= (await import('../entries/pages/_layout.svelte.js')).default;
export { universal };
export const universal_id = "src/routes/+layout.js";
export const imports = ["_app/immutable/nodes/0.BOF-AQtE.js","_app/immutable/chunks/r6Fdn0DE.js","_app/immutable/chunks/ZRn7W4Pn.js","_app/immutable/chunks/VEkpLhj1.js","_app/immutable/chunks/Sv3GaC-0.js","_app/immutable/chunks/C-_mf-Dw.js","_app/immutable/chunks/DV1UdSew.js","_app/immutable/chunks/D9qOUi3J.js","_app/immutable/chunks/BinfTu3E.js"];
export const stylesheets = ["_app/immutable/assets/0.BYexU6Qm.css"];
export const fonts = [];

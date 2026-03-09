export const manifest = (() => {
function __memo(fn) {
	let value;
	return () => value ??= (value = fn());
}

return {
	appDir: "_app",
	appPath: "admin-ui/_app",
	assets: new Set(["README.txt"]),
	mimeTypes: {".txt":"text/plain"},
	_: {
		client: {start:"_app/immutable/entry/start.CSvLRpNj.js",app:"_app/immutable/entry/app.DqqVQ0T0.js",imports:["_app/immutable/entry/start.CSvLRpNj.js","_app/immutable/chunks/Cplp6hmO.js","_app/immutable/chunks/Cgcn8Wh1.js","_app/immutable/entry/app.DqqVQ0T0.js","_app/immutable/chunks/Cgcn8Wh1.js","_app/immutable/chunks/BxAtPitE.js","_app/immutable/chunks/D2MBradB.js","_app/immutable/chunks/BRXuW3nO.js"],stylesheets:[],fonts:[],uses_env_dynamic_public:false},
		nodes: [
			__memo(() => import('./nodes/0.js')),
			__memo(() => import('./nodes/1.js'))
		],
		remotes: {
			
		},
		routes: [
			
		],
		prerendered_routes: new Set(["/admin-ui/","/admin-ui/access","/admin-ui/audit","/admin-ui/models","/admin-ui/sessions","/admin-ui/versions"]),
		matchers: async () => {
			
			return {  };
		},
		server_assets: {}
	}
}
})();

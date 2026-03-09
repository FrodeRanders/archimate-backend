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
		client: {start:"_app/immutable/entry/start.C4j8ImB4.js",app:"_app/immutable/entry/app.LfHTV8Mr.js",imports:["_app/immutable/entry/start.C4j8ImB4.js","_app/immutable/chunks/D9qOUi3J.js","_app/immutable/chunks/ZRn7W4Pn.js","_app/immutable/entry/app.LfHTV8Mr.js","_app/immutable/chunks/ZRn7W4Pn.js","_app/immutable/chunks/r6Fdn0DE.js","_app/immutable/chunks/BXAmtpCH.js","_app/immutable/chunks/DV1UdSew.js"],stylesheets:[],fonts:[],uses_env_dynamic_public:false},
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

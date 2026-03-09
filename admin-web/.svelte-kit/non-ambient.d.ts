
// this file is generated — do not edit it


declare module "svelte/elements" {
	export interface HTMLAttributes<T> {
		'data-sveltekit-keepfocus'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-noscroll'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-preload-code'?:
			| true
			| ''
			| 'eager'
			| 'viewport'
			| 'hover'
			| 'tap'
			| 'off'
			| undefined
			| null;
		'data-sveltekit-preload-data'?: true | '' | 'hover' | 'tap' | 'off' | undefined | null;
		'data-sveltekit-reload'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-replacestate'?: true | '' | 'off' | undefined | null;
	}
}

export {};


declare module "$app/types" {
	export interface AppTypes {
		RouteId(): "/" | "/access" | "/audit" | "/models" | "/sessions" | "/versions";
		RouteParams(): {
			
		};
		LayoutParams(): {
			"/": Record<string, never>;
			"/access": Record<string, never>;
			"/audit": Record<string, never>;
			"/models": Record<string, never>;
			"/sessions": Record<string, never>;
			"/versions": Record<string, never>
		};
		Pathname(): "/" | "/access" | "/audit" | "/models" | "/sessions" | "/versions";
		ResolvedPathname(): `${"" | `/${string}`}${ReturnType<AppTypes['Pathname']>}`;
		Asset(): "/README.txt" | string & {};
	}
}
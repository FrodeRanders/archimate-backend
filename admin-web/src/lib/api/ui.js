import { authUiConfig } from '$lib/stores/auth.js';

export const loadAdminUiConfig = async () => {
  try {
    const response = await fetch('/admin-ui/config');
    if (!response.ok) {
      throw new Error(`Admin UI config failed: HTTP ${response.status}`);
    }
    const payload = await response.json();
    authUiConfig.set({
      identityMode: payload.identityMode || 'bootstrap',
      authorizationEnabled: Boolean(payload.authorizationEnabled),
      loaded: true
    });
    return payload;
  } catch {
    authUiConfig.set({
      identityMode: 'bootstrap',
      authorizationEnabled: false,
      loaded: true
    });
    return null;
  }
};

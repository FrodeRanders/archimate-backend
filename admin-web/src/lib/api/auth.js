import { authDiagnostics, authSummary } from '$lib/stores/auth.js';
import { fetchJson, safe } from '$lib/api/client.js';
import { get } from 'svelte/store';
import { authToken } from '$lib/stores/auth.js';

export const decodeJwtPayload = (token) => {
  const parts = String(token || '').trim().split('.');
  if (parts.length < 2) {
    throw new Error('Bearer token format is invalid.');
  }
  const normalized = parts[1].replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
  return JSON.parse(atob(padded));
};

export const describeTokenStatus = (token) => {
  const trimmed = String(token || '').trim();
  if (!trimmed) return 'No bearer token set.';
  try {
    const payload = decodeJwtPayload(trimmed);
    if (typeof payload.exp !== 'number') return 'Bearer token has no exp claim.';
    const expiresAt = new Date(payload.exp * 1000);
    const formatted = expiresAt.toLocaleString();
    if (expiresAt.getTime() <= Date.now()) return `Bearer token expired at ${formatted}.`;
    return `Bearer token expires at ${formatted}.`;
  } catch (err) {
    return `Bearer token cannot be inspected locally: ${err.message}`;
  }
};

export const describeTokenIdentity = (token) => {
  const trimmed = String(token || '').trim();
  if (!trimmed) return 'No bearer token identity available.';
  try {
    const payload = decodeJwtPayload(trimmed);
    const subject = payload.preferred_username || payload.sub || payload.email || '';
    const roles = [];
    if (Array.isArray(payload.groups)) roles.push(...payload.groups);
    if (Array.isArray(payload.roles)) roles.push(...payload.roles);
    if (Array.isArray(payload.permissions)) roles.push(...payload.permissions);
    if (typeof payload.scope === 'string') roles.push(...payload.scope.split(/\s+/).filter(Boolean));
    const unique = [...new Set(roles.map((value) => String(value).trim()).filter(Boolean))];
    if (!subject && !unique.length) return 'Bearer token subject and role claims were not found.';
    if (!subject) return `Bearer token roles: ${unique.join(', ')}.`;
    if (!unique.length) return `Bearer token subject: ${subject}.`;
    return `Bearer token subject: ${subject}. Roles: ${unique.join(', ')}.`;
  } catch (err) {
    return `Bearer token identity cannot be inspected locally: ${err.message}`;
  }
};

export const refreshAuthDiagnostics = async () => {
  authSummary.set('Checking current request identity...');
  try {
    const diagnostics = await fetchJson('/admin/auth/diagnostics', { action: 'Auth check' });
    authDiagnostics.set(diagnostics);
    const roles = Array.isArray(diagnostics.normalizedRoles) && diagnostics.normalizedRoles.length
      ? diagnostics.normalizedRoles.join(', ')
      : 'none';
    const source = diagnostics.identityMode === 'oidc'
      ? (get(authToken).trim() ? 'bearer token' : 'container-managed identity')
      : diagnostics.identityMode === 'proxy'
        ? 'trusted forwarded headers'
        : 'bootstrap headers';
    authSummary.set(`Mode ${safe(diagnostics.identityMode)}. Subject ${safe(diagnostics.userId || 'anonymous')}. Roles ${roles}. Source ${source}.`);
    return diagnostics;
  } catch (err) {
    authDiagnostics.set(null);
    authSummary.set(`Auth check failed: ${err.message}`);
    throw err;
  }
};

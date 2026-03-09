import "./auth.js";
import "clsx";
const decodeJwtPayload = (token) => {
  const parts = String(token || "").trim().split(".");
  if (parts.length < 2) {
    throw new Error("Bearer token format is invalid.");
  }
  const normalized = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  return JSON.parse(atob(padded));
};
const describeTokenStatus = (token) => {
  const trimmed = String(token || "").trim();
  if (!trimmed) return "No bearer token set.";
  try {
    const payload = decodeJwtPayload(trimmed);
    if (typeof payload.exp !== "number") return "Bearer token has no exp claim.";
    const expiresAt = new Date(payload.exp * 1e3);
    const formatted = expiresAt.toLocaleString();
    if (expiresAt.getTime() <= Date.now()) return `Bearer token expired at ${formatted}.`;
    return `Bearer token expires at ${formatted}.`;
  } catch (err) {
    return `Bearer token cannot be inspected locally: ${err.message}`;
  }
};
const describeTokenIdentity = (token) => {
  const trimmed = String(token || "").trim();
  if (!trimmed) return "No bearer token identity available.";
  try {
    const payload = decodeJwtPayload(trimmed);
    const subject = payload.preferred_username || payload.sub || payload.email || "";
    const roles = [];
    if (Array.isArray(payload.groups)) roles.push(...payload.groups);
    if (Array.isArray(payload.roles)) roles.push(...payload.roles);
    if (Array.isArray(payload.permissions)) roles.push(...payload.permissions);
    if (typeof payload.scope === "string") roles.push(...payload.scope.split(/\s+/).filter(Boolean));
    const unique = [...new Set(roles.map((value) => String(value).trim()).filter(Boolean))];
    if (!subject && !unique.length) return "Bearer token subject and role claims were not found.";
    if (!subject) return `Bearer token roles: ${unique.join(", ")}.`;
    if (!unique.length) return `Bearer token subject: ${subject}.`;
    return `Bearer token subject: ${subject}. Roles: ${unique.join(", ")}.`;
  } catch (err) {
    return `Bearer token identity cannot be inspected locally: ${err.message}`;
  }
};
export {
  describeTokenIdentity as a,
  describeTokenStatus as d
};

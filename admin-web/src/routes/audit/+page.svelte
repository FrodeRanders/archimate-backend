<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import { fetchAuditConfig } from '$lib/api/models.js';

  let config = null;
  let pageStatus = 'Loading audit configuration...';

  const refresh = async () => {
    pageStatus = 'Refreshing audit configuration...';
    try {
      config = await fetchAuditConfig();
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const copyConfig = async () => {
    if (!config) {
      pageStatus = 'No audit configuration loaded.';
      return;
    }
    try {
      await navigator.clipboard.writeText(JSON.stringify(config, null, 2));
      pageStatus = 'Audit configuration copied';
    } catch (err) {
      pageStatus = `Copy failed: ${err.message}`;
    }
  };

  onMount(refresh);
</script>

<div class="hero">
  <div>
    <div class="eyebrow">Audit</div>
    <h1>Structured audit configuration and operator guidance.</h1>
    <p>This route makes the active audit settings visible without forcing operators back into README files or server configs.</p>
  </div>
  <div class="actions">
    <button on:click={refresh}>Refresh</button>
    <button on:click={copyConfig}>Copy Config</button>
  </div>
</div>

<div class="grid">
  <Panel title="Current Config" subtitle="Effective audit-related runtime values exposed by the server.">
    {#if config}
      <div class="stack">
        <div class="line"><strong>Identity mode</strong><span>{config.identityMode}</span></div>
        <div class="line"><strong>Authorization enabled</strong><span>{String(config.authorizationEnabled)}</span></div>
        <div class="line"><strong>WebSocket verbose audit</strong><span>{String(config.websocketAuditVerbose)}</span></div>
        <div class="line column">
          <strong>WebSocket audit actions</strong>
          <div class="chips">
            {#if config.websocketAuditActions.length === 0}
              <span class="empty">disabled</span>
            {:else}
              {#each config.websocketAuditActions as action}
                <code>{action}</code>
              {/each}
            {/if}
          </div>
        </div>
      </div>
    {:else}
      <div class="empty">Audit configuration is not available yet.</div>
    {/if}
  </Panel>

  <Panel title="Operator Guidance" subtitle="What to pay attention to when audit is enabled.">
    <div class="stack">
      <div class="guide">
        <strong>`admin_audit`</strong>
        <span>Machine-readable JSON for diagnostics access and mutating admin actions. Use a dedicated sink or index if operators depend on it.</span>
      </div>
      <div class="guide">
        <strong>`ws_audit`</strong>
        <span>Lifecycle-oriented websocket trail. Keep `app.audit.websocket.actions` narrow unless you explicitly need broad session visibility.</span>
      </div>
      <div class="guide">
        <strong>Verbose mode</strong>
        <span>`app.audit.websocket.verbose=true` also emits accepted websocket messages and increases volume noticeably.</span>
      </div>
      <div class="guide">
        <strong>Retention</strong>
        <span>Keep audit retention shorter than ordinary application logs if the admin dashboard refresh interval is low.</span>
      </div>
    </div>
  </Panel>
</div>

<div class="footer-status">{pageStatus}</div>

<style>
  .hero {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 1rem;
    align-items: end;
    padding: 1.1rem 1.2rem;
    border: 1px solid var(--line);
    border-radius: 1.2rem;
    background: linear-gradient(135deg, rgba(245, 158, 11, 0.12), rgba(56, 189, 248, 0.08));
  }
  .eyebrow {
    text-transform: uppercase;
    letter-spacing: 0.14rem;
    font-size: 0.78rem;
    color: var(--text-muted);
    margin-bottom: 0.5rem;
  }
  h1 {
    margin: 0;
    font-size: 1.7rem;
    max-width: 18ch;
  }
  p {
    margin: 0.45rem 0 0;
    color: var(--text-soft);
    max-width: 62ch;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
  }
  .stack {
    display: grid;
    gap: 0.85rem;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }
  .line.column {
    display: grid;
  }
  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: 0.55rem;
    margin-top: 0.5rem;
  }
  code {
    color: #fbbf24;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
    border-radius: 999px;
    padding: 0.2rem 0.55rem;
  }
  .guide {
    display: grid;
    gap: 0.25rem;
    padding: 0.9rem;
    border-radius: 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
  }
  .guide strong {
    color: var(--text);
  }
  .guide span,
  .empty,
  .footer-status {
    color: var(--text-muted);
  }
  @media (max-width: 1000px) {
    .hero,
    .grid {
      grid-template-columns: 1fr;
    }
  }
</style>

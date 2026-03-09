<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import { fetchModelWindow, fetchOverview } from '$lib/api/models.js';

  let overview = [];
  let selectedModelId = '';
  let selectedWindow = null;
  let pageStatus = 'Loading sessions...';
  let copiedAt = '';

  const refresh = async () => {
    pageStatus = 'Refreshing session diagnostics...';
    try {
      overview = await fetchOverview(100);
      if (!selectedModelId && overview.length) {
        selectedModelId = overview[0].modelId;
      }
      if (selectedModelId) {
        selectedWindow = await fetchModelWindow(selectedModelId, 25);
      } else {
        selectedWindow = null;
      }
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (event) => {
    selectedModelId = event.currentTarget.value;
    await refresh();
  };

  const copySessions = async () => {
    if (!selectedWindow) {
      pageStatus = 'Select a model first.';
      return;
    }
    const payload = {
      modelId: selectedWindow.modelId,
      modelName: selectedWindow.modelName,
      activeSessionCount: selectedWindow.activeSessionCount,
      activeSessions: selectedWindow.activeSessions || []
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(payload, null, 2));
      copiedAt = new Date().toLocaleTimeString();
      pageStatus = `Session payload copied at ${copiedAt}`;
    } catch (err) {
      pageStatus = `Copy failed: ${err.message}`;
    }
  };

  const writableCount = () => (selectedWindow?.activeSessions || []).filter((session) => session.writable).length;
  const readonlyCount = () => (selectedWindow?.activeSessions || []).filter((session) => !session.writable).length;

  onMount(refresh);
</script>

<PageHero
  eyebrow="Sessions"
  title="Live websocket session diagnostics."
  description="Inspect joined sessions, read-only tag usage, and writability without mixing this into model administration."
>
  <button on:click={refresh}>Refresh</button>
  <button on:click={copySessions}>Copy Sessions</button>
</PageHero>

<div class="grid">
  <Panel title="Models" subtitle="Choose the model whose live collaboration sessions you want to inspect.">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Selected</th>
            <th>Model</th>
            <th>Name</th>
            <th>Sessions</th>
            <th>Tags</th>
          </tr>
        </thead>
        <tbody>
          {#if overview.length === 0}
            <tr><td colspan="5" class="empty">No models available.</td></tr>
          {:else}
            {#each overview as row}
              <tr class:selected={row.modelId === selectedModelId}>
                <td><input type="radio" name="session-model" value={row.modelId} checked={row.modelId === selectedModelId} on:change={chooseModel} /></td>
                <td>{row.modelId}</td>
                <td>{row.modelName || ''}</td>
                <td>{row.activeSessionCount || 0}</td>
                <td>{row.tagSummary?.tagCount || 0}</td>
              </tr>
            {/each}
          {/if}
        </tbody>
      </table>
    </div>
  </Panel>

  <Panel title="Session Summary" subtitle="Current session counts for the selected model.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Name</strong><span>{selectedWindow.modelName || 'n/a'}</span></div>
        <div class="line"><strong>Total sessions</strong><span>{selectedWindow.activeSessionCount || 0}</span></div>
        <div class="line"><strong>Writable HEAD sessions</strong><span>{writableCount()}</span></div>
        <div class="line"><strong>Read-only tag sessions</strong><span>{readonlyCount()}</span></div>
        {#if copiedAt}
          <div class="line"><strong>Last copy</strong><span>{copiedAt}</span></div>
        {/if}
      </div>
    {:else}
      <div class="empty">Select a model to see session diagnostics.</div>
    {/if}
  </Panel>
</div>

<Panel title="Active Sessions" subtitle="Resolved user, normalized roles, joined ref, and writability.">
  {#if !selectedWindow || !selectedWindow.activeSessions || selectedWindow.activeSessions.length === 0}
    <div class="empty">No active joined sessions for the selected model.</div>
  {:else}
    <div class="cards">
      {#each selectedWindow.activeSessions as session}
        <article class="session-card">
          <div class="card-top">
            <strong>{session.userId || 'anonymous'}</strong>
            {#if session.writable}
              <StatusPill mode="open" label="writable" />
            {:else}
              <StatusPill mode="acl" label="read-only" />
            {/if}
          </div>
          <div class="meta-row"><span>WebSocket</span><code>{session.websocketSessionId}</code></div>
          <div class="meta-row"><span>Joined ref</span><code>{session.ref || 'HEAD'}</code></div>
          <div class="meta-row"><span>Roles</span><span>{session.normalizedRoles?.length ? session.normalizedRoles.join(', ') : 'none'}</span></div>
        </article>
      {/each}
    </div>
  {/if}
</Panel>

<div class="footer-status">{pageStatus}</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .table-wrap {
    overflow-x: auto;
  }
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  .line, .meta-row {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }
  .cards {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 0.9rem;
  }
  .session-card {
    padding: 0.95rem;
    border-radius: 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
  }
  .card-top {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: center;
    margin-bottom: 0.8rem;
  }
  code {
    color: #fbbf24;
    word-break: break-all;
  }
  tr.selected {
    background: rgba(245, 158, 11, 0.08);
  }
  .empty,
  .footer-status {
    color: var(--text-muted);
  }
  @media (max-width: 1000px) {
    .grid {
      grid-template-columns: 1fr;
    }
  }
</style>

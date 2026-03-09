<script>
  import { onMount } from 'svelte';
  import { get } from 'svelte/store';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import { authSummary, authToken, authUser, authRoles, pollSeconds } from '$lib/stores/auth.js';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { fetchModelWindow, fetchOverview } from '$lib/api/models.js';
  import { describeTokenIdentity, describeTokenStatus, refreshAuthDiagnostics } from '$lib/api/auth.js';
  import { safe } from '$lib/api/client.js';

  let overview = [];
  let selectedWindow = null;
  let status = 'Loading overview...';
  let loading = false;
  let timer = null;
  let limit = 25;
  let accessFilter = 'all';

  const filterOverview = (rows) => rows.filter((row) => {
    if (accessFilter === 'acl') return Boolean(row.accessSummary?.aclConfigured);
    if (accessFilter === 'open') return !row.accessSummary?.aclConfigured;
    return true;
  });

  const refresh = async () => {
    loading = true;
    status = 'Refreshing overview...';
    try {
      const payload = await fetchOverview(limit);
      overview = filterOverview(payload || []);
      const currentSelection = get(selectedModelId);
      if (!currentSelection && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      const modelId = get(selectedModelId);
      if (modelId) {
        selectedWindow = await fetchModelWindow(modelId, limit);
      } else {
        selectedWindow = null;
      }
      status = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      status = err.message;
    } finally {
      loading = false;
    }
  };

  const restartPolling = () => {
    if (timer) clearInterval(timer);
    const seconds = Math.max(0, Math.min(300, parseInt(get(pollSeconds) || '4', 10) || 0));
    pollSeconds.set(String(seconds));
    if (seconds > 0) timer = setInterval(refresh, seconds * 1000);
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const runAuthCheck = async () => {
    try {
      await refreshAuthDiagnostics();
      status = `Auth resolved at ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      status = err.message;
    }
  };

  const handlePollChange = () => {
    restartPolling();
    status = get(pollSeconds) === '0'
      ? 'Auto-refresh disabled.'
      : `Auto-refresh every ${get(pollSeconds)}s.`;
  };

  $: tokenStatus = describeTokenStatus($authToken);
  $: tokenIdentity = describeTokenIdentity($authToken);

  onMount(async () => {
    restartPolling();
    await refresh();
    return () => {
      if (timer) clearInterval(timer);
    };
  });
</script>

<PageHero
  eyebrow="Overview"
  title="Server state, identity context, and selected model focus."
  description="This is the operator landing page for the new admin app."
>
  <button on:click={runAuthCheck}>Auth Check</button>
  <button on:click={refresh} disabled={loading}>{loading ? 'Refreshing...' : 'Refresh'}</button>
</PageHero>

<div class="top-grid">
  <Panel title="Auth Inputs" subtitle="Shared inputs for bootstrap and bearer-token flows.">
    <div class="field-grid">
      <label>
        <span>Bearer Token</span>
        <textarea rows="4" bind:value={$authToken} placeholder="JWT token for oidc mode"></textarea>
      </label>
      <label>
        <span>Bootstrap User</span>
        <input bind:value={$authUser} placeholder="admin-user" />
      </label>
      <label>
        <span>Bootstrap Roles</span>
        <input bind:value={$authRoles} placeholder="admin,model_writer" />
      </label>
      <label>
        <span>Poll Seconds</span>
        <input type="number" min="0" max="300" bind:value={$pollSeconds} on:change={handlePollChange} />
      </label>
      <label>
        <span>Overview Limit</span>
        <input type="number" min="1" max="200" bind:value={limit} />
      </label>
      <label>
        <span>Access Filter</span>
        <select bind:value={accessFilter} on:change={refresh}>
          <option value="all">all</option>
          <option value="open">open</option>
          <option value="acl">acl</option>
        </select>
      </label>
    </div>
    <div class="hint-grid">
      <div><strong>Token status</strong><span>{tokenStatus}</span></div>
      <div><strong>Token preview</strong><span>{tokenIdentity}</span></div>
      <div><strong>Resolved identity</strong><span>{$authSummary}</span></div>
    </div>
  </Panel>

  <Panel title="Selected Model" subtitle="Focused window summary for the active row.">
    {#if selectedWindow}
      <div class="summary-stack">
        <div class="summary-row"><span>Model</span><strong>{selectedWindow.modelId}</strong></div>
        <div class="summary-row"><span>Name</span><strong>{selectedWindow.modelName || 'n/a'}</strong></div>
        <div class="summary-row"><span>Access</span><span>{#if selectedWindow.accessSummary?.aclConfigured}<StatusPill mode="acl" label="acl" />{:else}<StatusPill mode="open" label="open" />{/if}</span></div>
        <div class="summary-row"><span>Tags</span><strong>{safe(selectedWindow.tagSummary?.tagCount || 0)}</strong></div>
        <div class="summary-row"><span>Latest Tag</span><strong>{selectedWindow.tagSummary?.latestTagName || 'none'}</strong></div>
        <div class="summary-row"><span>Sessions</span><strong>{safe(selectedWindow.activeSessionCount || 0)}</strong></div>
        <div class="summary-row"><span>Consistency</span><strong>{String(selectedWindow.integrity?.consistent ?? false)}</strong></div>
      </div>
    {:else}
      <p class="empty">Select a model to see its focused status.</p>
    {/if}
  </Panel>
</div>

<Panel title="Known Models" subtitle="Model list migrated out of the monolithic admin page.">
  <table>
    <thead>
      <tr>
        <th>Model</th>
        <th>Name</th>
        <th>Access</th>
        <th>Sessions</th>
        <th>Tags</th>
        <th>Latest Tag</th>
        <th>Snapshot Head</th>
        <th>Persisted</th>
        <th>Commit</th>
      </tr>
    </thead>
    <tbody>
      {#if overview.length === 0}
        <tr><td colspan="9" class="empty">No models match the current filter.</td></tr>
      {:else}
        {#each overview as row}
          <tr class:selected={row.modelId === $selectedModelId}>
            <td><button class="row-button" on:click={() => chooseModel(row)}>{row.modelId}</button></td>
            <td>{row.modelName || ''}</td>
            <td>
              {#if row.accessSummary?.aclConfigured}
                <StatusPill mode="acl" label="acl" />
              {:else}
                <StatusPill mode="open" label="open" />
              {/if}
            </td>
            <td>{safe(row.activeSessionCount || 0)}</td>
            <td>{safe(row.tagSummary?.tagCount || 0)}</td>
            <td>{row.tagSummary?.latestTagName || 'none'}</td>
            <td>{safe(row.snapshotHeadRevision)}</td>
            <td>{safe(row.persistedHeadRevision)}</td>
            <td>{safe(row.lastCommitRevision)}</td>
          </tr>
        {/each}
      {/if}
    </tbody>
  </table>
</Panel>

<div class="footer-status">{status}</div>

<style>
  .top-grid { display:grid; grid-template-columns: 1.3fr 0.9fr; gap:1rem; }
  .field-grid { display:grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap:0.9rem; }
  label { display:flex; flex-direction:column; gap:0.45rem; color:var(--text-soft); }
  .hint-grid { margin-top:1rem; display:grid; gap:0.7rem; }
  .hint-grid div { display:grid; gap:0.18rem; padding:0.8rem 0.9rem; border-radius:0.9rem; background:rgba(255,255,255,0.03); }
  .hint-grid strong { font-size:0.8rem; letter-spacing:0.08rem; text-transform:uppercase; color:var(--text-muted); }
  .summary-stack { display:grid; gap:0.7rem; }
  .summary-row { display:flex; justify-content:space-between; gap:1rem; align-items:center; padding-bottom:0.45rem; border-bottom:1px solid rgba(255,255,255,0.05); }
  .summary-row span:first-child { color:var(--text-muted); }
  .empty { color:var(--text-muted); }
  .row-button { background:transparent; border:none; padding:0; color:var(--text); text-decoration:underline; text-underline-offset:0.18rem; }
  tr.selected { background: rgba(245,158,11,0.08); }
  .footer-status { color:var(--text-muted); font-size:0.9rem; }
  @media (max-width: 1000px) {
    .top-grid, .field-grid { grid-template-columns:1fr; }
  }
</style>

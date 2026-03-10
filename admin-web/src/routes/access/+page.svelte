<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { authSummary, authToken, authUser, authRoles } from '$lib/stores/auth.js';
  import { describeTokenIdentity, describeTokenStatus, refreshAuthDiagnostics } from '$lib/api/auth.js';
  import { fetchModelAcl, fetchOverview, saveModelAcl } from '$lib/api/models.js';

  let overview = [];
  let accessSummary = null;
  let authStatus = '';
  let pageStatus = 'Loading access controls...';
  let adminUsers = '';
  let writerUsers = '';
  let readerUsers = '';

  const formatUsers = (values) => (Array.isArray(values) ? values.join('\n') : '');
  const parseUsers = (raw) => Array.from(new Set(
    String(raw || '')
      .split(/[\n,]/)
      .map((value) => value.trim())
      .filter(Boolean)
  ));

  const loadOverview = async () => {
    overview = await fetchOverview(100);
    if (!$selectedModelId && overview.length) {
      selectedModelId.set(overview[0].modelId);
    }
    accessSummary = overview.find((row) => row.modelId === $selectedModelId)?.accessSummary || null;
  };

  const loadAcl = async () => {
    if (!$selectedModelId) {
      adminUsers = '';
      writerUsers = '';
      readerUsers = '';
      accessSummary = null;
      return;
    }
    const acl = await fetchModelAcl($selectedModelId);
    adminUsers = formatUsers(acl.adminUsers);
    writerUsers = formatUsers(acl.writerUsers);
    readerUsers = formatUsers(acl.readerUsers);
    accessSummary = overview.find((row) => row.modelId === $selectedModelId)?.accessSummary || null;
  };

  const refreshAll = async () => {
    pageStatus = 'Refreshing access data...';
    try {
      await loadOverview();
      await loadAcl();
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const runAuthCheck = async () => {
    try {
      await refreshAuthDiagnostics();
      authStatus = `Resolved ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      authStatus = err.message;
    }
  };

  const persistAcl = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Saving ACL for ${$selectedModelId}...`;
    try {
      await saveModelAcl($selectedModelId, {
        adminUsers: parseUsers(adminUsers),
        writerUsers: parseUsers(writerUsers),
        readerUsers: parseUsers(readerUsers)
      });
      await refreshAll();
      pageStatus = `ACL updated for ${$selectedModelId}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await loadAcl();
    pageStatus = `Selected ${row.modelId}`;
  };

  $: tokenStatus = describeTokenStatus($authToken);
  $: tokenIdentity = describeTokenIdentity($authToken);

  onMount(refreshAll);
</script>

<PageHero
  eyebrow="Access"
  title="Identity diagnostics and model ACLs."
  description="Authentication controls stay together above the ACL editor. Model selection stays on the left. ACL edits stay beside the selected model."
>
  <button class="secondary" on:click={runAuthCheck}>Auth Check</button>
  <button class="primary" on:click={refreshAll}>Refresh</button>
</PageHero>

<div class="grid top-grid">
  <Panel title="Current Identity" subtitle="Resolved request context and local token guidance.">
    <div class="stack">
      <div class="line"><strong>Resolved identity</strong><span>{$authSummary}</span></div>
      <div class="line"><strong>Token status</strong><span>{tokenStatus}</span></div>
      <div class="line"><strong>Token preview</strong><span>{tokenIdentity}</span></div>
      {#if authStatus}
        <div class="line"><strong>Last auth check</strong><span>{authStatus}</span></div>
      {/if}
    </div>
  </Panel>

  <Panel title="Input Mode" subtitle="Use bootstrap fields for local testing or a bearer token for oidc mode.">
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
    </div>
  </Panel>
</div>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Model Access Focus"
      subtitle="Choose the model whose ACL you want to edit."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="ACL Editor" subtitle="The save action affects only the selected model shown here.">
    <div class="stack">
      <div class="line">
        <strong>Selected model</strong>
        <span>{$selectedModelId || 'none'}</span>
      </div>
      <div class="line">
        <strong>Access mode</strong>
        <span>
          {#if accessSummary?.aclConfigured}
            <StatusPill mode="acl" label="acl" />
          {:else}
            <StatusPill mode="open" label="open" />
          {/if}
        </span>
      </div>
      <div class="field-grid single">
        <label>
          <span>Model admins</span>
          <textarea rows="5" bind:value={adminUsers} placeholder="owner-user&#10;backup-admin"></textarea>
        </label>
        <label>
          <span>Writers</span>
          <textarea rows="5" bind:value={writerUsers} placeholder="editor-a&#10;editor-b"></textarea>
        </label>
        <label>
          <span>Readers</span>
          <textarea rows="5" bind:value={readerUsers} placeholder="viewer-a&#10;viewer-b"></textarea>
        </label>
      </div>
      <div class="actions">
        <button class="primary" on:click={persistAcl} disabled={!$selectedModelId}>Save ACL</button>
      </div>
    </div>
  </Panel>
</SplitView>

<div class="footer-status">{pageStatus}</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .field-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0.9rem;
  }
  .field-grid.single {
    grid-template-columns: 1fr;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    color: var(--text-soft);
  }
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  .line {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: flex-start;
    padding-bottom: 0.45rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }
  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
  }
  .footer-status {
    color: var(--text-muted);
  }
  @media (max-width: 1000px) {
    .grid,
    .field-grid {
      grid-template-columns: 1fr;
    }
  }
</style>

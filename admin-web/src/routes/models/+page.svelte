<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import {
    compactModel,
    createModel,
    deleteModel,
    fetchModelWindow,
    fetchOverview,
    rebuildModel,
    renameModel
  } from '$lib/api/models.js';

  let overview = [];
  let selectedWindow = null;
  let pageStatus = 'Loading models...';
  let newModelId = '';
  let newModelName = '';
  let renameValue = '';
  let compactRetain = '0';
  let deleteForce = false;

  const refresh = async () => {
    pageStatus = 'Refreshing model lifecycle data...';
    try {
      overview = await fetchOverview(100);
      if (!$selectedModelId && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      if ($selectedModelId) {
        selectedWindow = await fetchModelWindow($selectedModelId, 25);
        renameValue = selectedWindow?.modelName || '';
      } else {
        selectedWindow = null;
      }
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const submitCreate = async () => {
    if (!newModelId.trim()) {
      pageStatus = 'New model id is required.';
      return;
    }
    pageStatus = `Creating ${newModelId}...`;
    try {
      await createModel(newModelId.trim(), newModelName.trim());
      selectedModelId.set(newModelId.trim());
      newModelId = '';
      newModelName = '';
      await refresh();
      pageStatus = `Model ${$selectedModelId} created`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRename = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Renaming ${$selectedModelId}...`;
    try {
      await renameModel($selectedModelId, renameValue.trim());
      await refresh();
      pageStatus = `Model ${$selectedModelId} renamed`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRebuild = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Rebuilding ${$selectedModelId}...`;
    try {
      await rebuildModel($selectedModelId);
      await refresh();
      pageStatus = `Model ${$selectedModelId} rebuilt`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitCompact = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Compacting ${$selectedModelId}...`;
    try {
      await compactModel($selectedModelId, compactRetain || '0');
      await refresh();
      pageStatus = `Model ${$selectedModelId} compacted`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitDelete = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Deleting ${$selectedModelId}...`;
    try {
      const target = $selectedModelId;
      const result = await deleteModel(target, deleteForce);
      if (result.deleted) {
        selectedModelId.set('');
      }
      await refresh();
      pageStatus = result.message || `Delete completed for ${target}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Models"
  title="Model lifecycle and maintenance."
  description="Select a model on the left. Each action card on the right only affects that selected model."
>
  <button class="secondary" on:click={refresh}>Refresh</button>
</PageHero>

<div class="top-grid">
  <Panel title="Create Model" subtitle="Creation is isolated so it does not compete with selection-based actions.">
    <div class="stack">
      <label>
        <span>Model ID</span>
        <input bind:value={newModelId} placeholder="demo-model" />
      </label>
      <label>
        <span>Model Name</span>
        <input bind:value={newModelName} placeholder="Demo Model" />
      </label>
      <div class="actions">
        <button class="primary" on:click={submitCreate}>Create Model</button>
      </div>
    </div>
  </Panel>

  <Panel title="Route Guidance" subtitle="The action groups below are intentionally separated by concern.">
    <div class="stack help">
      <div class="line"><strong>Naming</strong><span>Rename sits next to the name field it edits.</span></div>
      <div class="line"><strong>Maintenance</strong><span>Rebuild and compact are grouped together.</span></div>
      <div class="line"><strong>Deletion</strong><span>Destructive controls are isolated in their own panel.</span></div>
    </div>
  </Panel>
</div>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Model Navigator"
      subtitle="Choose the model you want to administer."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <Panel title="Focused Status" subtitle="Operational summary for the selected model.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Name</strong><span>{selectedWindow.modelName || 'n/a'}</span></div>
        <div class="line"><strong>Access</strong><span>{#if selectedWindow.accessSummary?.aclConfigured}<StatusPill mode="acl" label="acl" />{:else}<StatusPill mode="open" label="open" />{/if}</span></div>
        <div class="line"><strong>Snapshot Head</strong><span>{selectedWindow.snapshotHeadRevision}</span></div>
        <div class="line"><strong>Persisted Head</strong><span>{selectedWindow.persistedHeadRevision}</span></div>
        <div class="line"><strong>Last Commit</strong><span>{selectedWindow.lastCommitRevision}</span></div>
        <div class="line"><strong>Recent Activity</strong><span>{selectedWindow.recentActivity?.length || 0}</span></div>
        <div class="line"><strong>Recent Op Batches</strong><span>{selectedWindow.recentOpBatches?.length || 0}</span></div>
        <div class="line"><strong>Integrity</strong><span>{String(selectedWindow.integrity?.consistent ?? false)}</span></div>
      </div>
    {:else}
      <div class="empty">Select a model to see focused status.</div>
    {/if}
  </Panel>

  <div class="grid">
    <Panel title="Naming" subtitle="The rename action lives next to the model name input.">
      <div class="stack">
        <label>
          <span>Model name</span>
          <input bind:value={renameValue} placeholder="Selected model name" />
        </label>
        <div class="actions">
          <button class="primary" on:click={submitRename} disabled={!$selectedModelId}>Rename</button>
        </div>
      </div>
    </Panel>

    <Panel title="Maintenance" subtitle="Rebuild and compaction are grouped because they affect repository state.">
      <div class="stack">
        <label>
          <span>Compact retain revisions</span>
          <input type="number" min="0" bind:value={compactRetain} />
        </label>
        <div class="actions">
          <button class="secondary" on:click={submitRebuild} disabled={!$selectedModelId}>Rebuild</button>
          <button class="primary" on:click={submitCompact} disabled={!$selectedModelId}>Compact</button>
        </div>
      </div>
    </Panel>
  </div>

  <Panel title="Deletion" subtitle="Destructive action is isolated so it cannot be confused with maintenance.">
    <div class="stack">
      <label class="checkbox">
        <input type="checkbox" bind:checked={deleteForce} />
        <span>Force delete even when sessions are active</span>
      </label>
      <div class="actions">
        <button class="danger" on:click={submitDelete} disabled={!$selectedModelId}>Delete Selected Model</button>
      </div>
    </div>
  </Panel>
</SplitView>

<div class="footer-status">{pageStatus}</div>

<style>
  .top-grid,
  .grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 1rem;
  }
  .stack {
    display: grid;
    gap: 0.8rem;
  }
  label {
    display: flex;
    flex-direction: column;
    gap: 0.45rem;
    color: var(--text-soft);
  }
  .checkbox {
    flex-direction: row;
    align-items: center;
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
  .help .line {
    border-bottom: none;
    padding-bottom: 0;
  }
  .empty,
  .footer-status {
    color: var(--text-muted);
  }
  @media (max-width: 1000px) {
    .top-grid,
    .grid {
      grid-template-columns: 1fr;
    }
  }
</style>

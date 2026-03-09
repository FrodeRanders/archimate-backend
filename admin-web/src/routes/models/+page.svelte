<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import StatusPill from '$lib/components/StatusPill.svelte';
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
  let selectedModelId = '';
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
      if (!selectedModelId && overview.length) {
        selectedModelId = overview[0].modelId;
      }
      if (selectedModelId) {
        selectedWindow = await fetchModelWindow(selectedModelId, 25);
        renameValue = selectedWindow?.modelName || '';
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

  const submitCreate = async () => {
    if (!newModelId.trim()) {
      pageStatus = 'New model id is required.';
      return;
    }
    pageStatus = `Creating ${newModelId}...`;
    try {
      await createModel(newModelId.trim(), newModelName.trim());
      selectedModelId = newModelId.trim();
      newModelId = '';
      newModelName = '';
      await refresh();
      pageStatus = `Model ${selectedModelId} created`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRename = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Renaming ${selectedModelId}...`;
    try {
      await renameModel(selectedModelId, renameValue.trim());
      await refresh();
      pageStatus = `Model ${selectedModelId} renamed`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitRebuild = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Rebuilding ${selectedModelId}...`;
    try {
      await rebuildModel(selectedModelId);
      await refresh();
      pageStatus = `Model ${selectedModelId} rebuilt`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitCompact = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Compacting ${selectedModelId}...`;
    try {
      await compactModel(selectedModelId, compactRetain || '0');
      await refresh();
      pageStatus = `Model ${selectedModelId} compacted`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const submitDelete = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Deleting ${selectedModelId}...`;
    try {
      const target = selectedModelId;
      const result = await deleteModel(target, deleteForce);
      if (result.deleted) {
        selectedModelId = '';
      }
      await refresh();
      pageStatus = result.message || `Delete completed for ${target}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  onMount(refresh);
</script>

<div class="hero">
  <div>
    <div class="eyebrow">Models</div>
    <h1>Model lifecycle and maintenance.</h1>
    <p>This route holds the administrative actions that change or repair model state, separate from access and version workflows.</p>
  </div>
  <div class="actions">
    <button on:click={refresh}>Refresh</button>
    <button on:click={submitRebuild}>Rebuild</button>
  </div>
</div>

<div class="grid">
  <Panel title="Create Model" subtitle="Register a new model before clients join it.">
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
        <button on:click={submitCreate}>Create Model</button>
      </div>
    </div>
  </Panel>

  <Panel title="Selected Model" subtitle="Current lifecycle actions for the active model.">
    <div class="stack">
      <div class="line"><strong>Model</strong><span>{selectedModelId || 'none'}</span></div>
      <label>
        <span>Rename</span>
        <input bind:value={renameValue} placeholder="Selected model name" />
      </label>
      <label>
        <span>Compact retain revisions</span>
        <input type="number" min="0" bind:value={compactRetain} />
      </label>
      <label class="checkbox">
        <input type="checkbox" bind:checked={deleteForce} />
        <span>Force delete even when sessions are active</span>
      </label>
      <div class="actions">
        <button on:click={submitRename}>Rename</button>
        <button on:click={submitRebuild}>Rebuild</button>
        <button on:click={submitCompact}>Compact</button>
        <button class="danger" on:click={submitDelete}>Delete</button>
      </div>
    </div>
  </Panel>
</div>

<div class="grid">
  <Panel title="Models" subtitle="Choose the model to inspect or administer.">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Selected</th>
            <th>Model</th>
            <th>Name</th>
            <th>Access</th>
            <th>Sessions</th>
            <th>Tags</th>
          </tr>
        </thead>
        <tbody>
          {#if overview.length === 0}
            <tr><td colspan="6" class="empty">No models available.</td></tr>
          {:else}
            {#each overview as row}
              <tr class:selected={row.modelId === selectedModelId}>
                <td><input type="radio" name="model" value={row.modelId} checked={row.modelId === selectedModelId} on:change={chooseModel} /></td>
                <td>{row.modelId}</td>
                <td>{row.modelName || ''}</td>
                <td>
                  {#if row.accessSummary?.aclConfigured}
                    <StatusPill mode="acl" label="acl" />
                  {:else}
                    <StatusPill mode="open" label="open" />
                  {/if}
                </td>
                <td>{row.activeSessionCount || 0}</td>
                <td>{row.tagSummary?.tagCount || 0}</td>
              </tr>
            {/each}
          {/if}
        </tbody>
      </table>
    </div>
  </Panel>

  <Panel title="Focused Status" subtitle="Operational summary for the selected model.">
    {#if selectedWindow}
      <div class="stack">
        <div class="line"><strong>Model</strong><span>{selectedWindow.modelId}</span></div>
        <div class="line"><strong>Name</strong><span>{selectedWindow.modelName || 'n/a'}</span></div>
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
  .table-wrap {
    overflow-x: auto;
  }
  tr.selected {
    background: rgba(245, 158, 11, 0.08);
  }
  .danger {
    border-color: rgba(248, 113, 113, 0.35);
    color: #fecaca;
  }
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

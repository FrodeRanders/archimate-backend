<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import { createModelTag, deleteModelTag, exportModelPackage, fetchModelTags, fetchOverview, importModelPackage } from '$lib/api/models.js';

  let overview = [];
  let selectedModelId = '';
  let pageStatus = 'Loading versions...';
  let tags = [];
  let exportJson = '';
  let importJson = '';
  let overwrite = false;
  let newTagName = '';
  let newTagDescription = '';

  const refresh = async () => {
    pageStatus = 'Refreshing versions...';
    try {
      overview = await fetchOverview(100);
      if (!selectedModelId && overview.length) {
        selectedModelId = overview[0].modelId;
      }
      if (selectedModelId) {
        tags = await fetchModelTags(selectedModelId);
      } else {
        tags = [];
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

  const createTag = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    if (!newTagName.trim()) {
      pageStatus = 'Tag name is required.';
      return;
    }
    pageStatus = `Creating tag ${newTagName}...`;
    try {
      await createModelTag(selectedModelId, newTagName.trim(), newTagDescription.trim());
      newTagName = '';
      newTagDescription = '';
      await refresh();
      pageStatus = `Tag created for ${selectedModelId}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const dropTag = async (tagName) => {
    if (!selectedModelId) {
      return;
    }
    pageStatus = `Deleting tag ${tagName}...`;
    try {
      await deleteModelTag(selectedModelId, tagName);
      await refresh();
      pageStatus = `Tag ${tagName} deleted`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const exportSelectedModel = async () => {
    if (!selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Exporting ${selectedModelId}...`;
    try {
      const payload = await exportModelPackage(selectedModelId);
      exportJson = JSON.stringify(payload, null, 2);
      pageStatus = `Exported ${selectedModelId}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const importPackage = async () => {
    if (!importJson.trim()) {
      pageStatus = 'Paste an export package first.';
      return;
    }
    pageStatus = 'Importing package...';
    try {
      const payload = JSON.parse(importJson);
      const result = await importModelPackage(payload, overwrite);
      selectedModelId = result.modelId || selectedModelId;
      await refresh();
      pageStatus = result.message || 'Import completed.';
    } catch (err) {
      pageStatus = err.message;
    }
  };

  onMount(refresh);
</script>

<PageHero
  eyebrow="Versions"
  title="Immutable tags, export, and import."
  description="Keep the linear versioning workflow separate from day-to-day model operations."
>
  <button on:click={refresh}>Refresh</button>
  <button on:click={exportSelectedModel}>Export Selected</button>
</PageHero>

<div class="grid">
  <Panel title="Models" subtitle="Pick the model whose tags or package you want to manage.">
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Selected</th>
            <th>Model</th>
            <th>Name</th>
            <th>Tags</th>
            <th>Latest Tag</th>
          </tr>
        </thead>
        <tbody>
          {#if overview.length === 0}
            <tr><td colspan="5" class="empty">No models available.</td></tr>
          {:else}
            {#each overview as row}
              <tr class:selected={row.modelId === selectedModelId}>
                <td><input type="radio" name="version-model" value={row.modelId} checked={row.modelId === selectedModelId} on:change={chooseModel} /></td>
                <td>{row.modelId}</td>
                <td>{row.modelName || ''}</td>
                <td>{row.tagSummary?.tagCount || 0}</td>
                <td>{row.tagSummary?.latestTagName || 'none'}</td>
              </tr>
            {/each}
          {/if}
        </tbody>
      </table>
    </div>
  </Panel>

  <Panel title="Create Tag" subtitle="Tags are immutable and captured from the current HEAD state.">
    <div class="stack">
      <label>
        <span>Tag name</span>
        <input bind:value={newTagName} placeholder="v1.2" />
      </label>
      <label>
        <span>Description</span>
        <input bind:value={newTagDescription} placeholder="approved-2026-03-09" />
      </label>
      <div class="actions">
        <button on:click={createTag}>Create Tag From HEAD</button>
      </div>
    </div>
  </Panel>
</div>

<div class="grid">
  <Panel title="Tags" subtitle="Historical pull points for the selected model.">
    <div class="stack">
      {#if tags.length === 0}
        <div class="empty">No tags for the selected model.</div>
      {:else}
        {#each tags as tag}
          <div class="tag-card">
            <div class="tag-top">
              <strong>{tag.tagName}</strong>
              <button on:click={() => dropTag(tag.tagName)}>Delete</button>
            </div>
            <div class="tag-meta">
              <span>revision {tag.revision}</span>
              <span>{tag.createdAt || 'n/a'}</span>
            </div>
            <div class="tag-description">{tag.description || 'No description.'}</div>
          </div>
        {/each}
      {/if}
    </div>
  </Panel>

  <Panel title="Import / Export" subtitle="Exports preserve metadata, op-log history, snapshot state, and tags.">
    <div class="stack">
      <label>
        <span>Export JSON</span>
        <textarea rows="10" bind:value={exportJson} placeholder="Exported package appears here after Export Selected"></textarea>
      </label>
      <label>
        <span>Import JSON</span>
        <textarea rows="10" bind:value={importJson} placeholder="Paste a package to import"></textarea>
      </label>
      <label class="checkbox">
        <input type="checkbox" bind:checked={overwrite} />
        <span>Overwrite existing model when import conflicts</span>
      </label>
      <div class="actions">
        <button on:click={importPackage}>Import Package</button>
      </div>
    </div>
  </Panel>
</div>

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
  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
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
  .tag-card {
    padding: 0.9rem;
    border-radius: 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
  }
  .tag-top,
  .tag-meta {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: center;
  }
  .tag-meta {
    color: var(--text-muted);
    font-size: 0.9rem;
    margin-top: 0.4rem;
  }
  .tag-description {
    margin-top: 0.6rem;
    color: var(--text-soft);
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

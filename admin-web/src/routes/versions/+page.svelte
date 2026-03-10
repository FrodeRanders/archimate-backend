<script>
  import { onMount } from 'svelte';
  import Panel from '$lib/components/Panel.svelte';
  import PageHero from '$lib/components/PageHero.svelte';
  import SplitView from '$lib/components/SplitView.svelte';
  import ModelNavigator from '$lib/components/ModelNavigator.svelte';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { createModelTag, deleteModelTag, exportModelPackage, fetchModelTags, fetchOverview, importModelPackage } from '$lib/api/models.js';

  let overview = [];
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
      if (!$selectedModelId && overview.length) {
        selectedModelId.set(overview[0].modelId);
      }
      tags = $selectedModelId ? await fetchModelTags($selectedModelId) : [];
      pageStatus = `Updated ${new Date().toLocaleTimeString()}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const chooseModel = async (row) => {
    selectedModelId.set(row.modelId);
    await refresh();
  };

  const createTag = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    if (!newTagName.trim()) {
      pageStatus = 'Tag name is required.';
      return;
    }
    pageStatus = `Creating tag ${newTagName}...`;
    try {
      await createModelTag($selectedModelId, newTagName.trim(), newTagDescription.trim());
      newTagName = '';
      newTagDescription = '';
      await refresh();
      pageStatus = `Tag created for ${$selectedModelId}`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const dropTag = async (tagName) => {
    if (!$selectedModelId) {
      return;
    }
    pageStatus = `Deleting tag ${tagName}...`;
    try {
      await deleteModelTag($selectedModelId, tagName);
      await refresh();
      pageStatus = `Tag ${tagName} deleted`;
    } catch (err) {
      pageStatus = err.message;
    }
  };

  const exportSelectedModel = async () => {
    if (!$selectedModelId) {
      pageStatus = 'Select a model first.';
      return;
    }
    pageStatus = `Exporting ${$selectedModelId}...`;
    try {
      const payload = await exportModelPackage($selectedModelId);
      exportJson = JSON.stringify(payload, null, 2);
      pageStatus = `Exported ${$selectedModelId}`;
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
      selectedModelId.set(result.modelId || $selectedModelId);
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
  title="Tags, export, and import."
  description="Choose a model, then manage its named snapshots or move it in and out as a package."
>
  <button class="secondary" on:click={refresh}>Refresh Versions</button>
</PageHero>

<SplitView>
  <svelte:fragment slot="sidebar">
    <ModelNavigator
      rows={overview}
      selectedId={$selectedModelId}
      title="Models"
      subtitle="Choose the model whose tags or package you want to manage."
      onSelect={chooseModel}
    />
  </svelte:fragment>

  <div class="grid">
    <Panel title="Create Tag" subtitle="Create an immutable named snapshot from the current HEAD state.">
      <div class="stack">
        <div class="line"><strong>Selected model</strong><span>{$selectedModelId || 'none'}</span></div>
        <label>
          <span>Tag name</span>
          <input bind:value={newTagName} placeholder="v1.2" />
        </label>
        <label>
          <span>Description</span>
          <input bind:value={newTagDescription} placeholder="approved-2026-03-09" />
        </label>
        <div class="actions">
          <button class="primary" on:click={createTag} disabled={!$selectedModelId}>Create Tag</button>
        </div>
      </div>
    </Panel>

    <Panel title="Existing Tags" subtitle="Named historical snapshots for the selected model.">
      <div class="stack">
        {#if tags.length === 0}
          <div class="empty">No tags for the selected model.</div>
        {:else}
          {#each tags as tag}
            <div class="tag-card">
              <div class="tag-top">
                <strong>{tag.tagName}</strong>
                <button class="ghost" on:click={() => dropTag(tag.tagName)}>Delete Tag</button>
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
  </div>

  <Panel title="Import / Export" subtitle="Export the selected model or import a package into the server.">
    <div class="stack">
      <div class="line"><strong>Selected model</strong><span>{$selectedModelId || 'none'}</span></div>
      <label>
        <span>Export JSON</span>
        <textarea rows="10" bind:value={exportJson} placeholder="Exported package appears here after export"></textarea>
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
        <button class="secondary" on:click={exportSelectedModel} disabled={!$selectedModelId}>Export Model</button>
        <button class="primary" on:click={importPackage}>Import Package</button>
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
    justify-content: flex-start;
    gap: 0.7rem;
  }
  .checkbox input {
    width: auto;
    flex: 0 0 auto;
  }
  .checkbox span {
    flex: 0 1 auto;
  }
  .line,
  .tag-top,
  .tag-meta {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
    align-items: center;
  }
  .line {
    padding-bottom: 0.45rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  }
  .tag-card {
    padding: 0.9rem;
    border-radius: 0.9rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.03);
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

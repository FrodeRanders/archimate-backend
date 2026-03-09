<script>
  import Panel from '$lib/components/Panel.svelte';
  export let title = 'Section';
  export let summary = '';
  export let next = [];
</script>

<Panel {title} subtitle={summary}>
  <div class="stack">
    <p>This route now exists as an independent concern boundary. The old monolithic admin page still carries the full implementation until this section is migrated.</p>
    {#if next.length}
      <div>
        <strong>Planned migration scope</strong>
        <ul>
          {#each next as item}
            <li>{item}</li>
          {/each}
        </ul>
      </div>
    {/if}
  </div>
</Panel>

<style>
  .stack { display:grid; gap:0.9rem; color:var(--text-soft); }
  p { margin:0; }
  ul { margin:0.4rem 0 0; padding-left:1.2rem; }
  li { margin:0.3rem 0; }
  strong { color:var(--text); }
</style>

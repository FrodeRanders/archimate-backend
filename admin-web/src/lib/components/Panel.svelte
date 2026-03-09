<script>
  export let title = '';
  export let subtitle = '';
</script>

<section class="panel">
  <header class="panel-header">
    <div>
      <h2>{title}</h2>
      {#if subtitle}<p>{subtitle}</p>{/if}
    </div>
    <slot name="actions" />
  </header>
  <div class="panel-body">
    <slot />
  </div>
</section>

<style>
  .panel {
    background: rgba(10, 14, 26, 0.84);
    border-radius: 1.1rem;
    border: 1px solid rgba(255,255,255,0.08);
    overflow: hidden;
  }
  .panel-header {
    display:flex; justify-content:space-between; gap:1rem; align-items:flex-start;
    padding: 1rem 1.2rem; border-bottom:1px solid rgba(255,255,255,0.06);
  }
  h2 { margin:0; font-size:0.95rem; text-transform:uppercase; letter-spacing:0.14rem; color: var(--text-muted); }
  p { margin:0.35rem 0 0; color: var(--text-soft); font-size:0.92rem; }
  .panel-body { padding: 1.2rem; }
</style>

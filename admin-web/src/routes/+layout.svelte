<script>
  import Header from '$lib/components/Header.svelte';
  import { initAuthStores } from '$lib/stores/auth.js';
  import { selectedModelId } from '$lib/stores/selection.js';
  import { onMount } from 'svelte';

  onMount(() => {
    initAuthStores();
    selectedModelId.init();
  });
</script>

<Header />
<main class="page">
  <slot />
</main>

<style>
  :global(:root) {
    --bg: #09101d;
    --bg-alt: #101a2d;
    --panel: rgba(10, 14, 26, 0.84);
    --accent: #f59e0b;
    --accent-soft: rgba(245, 158, 11, 0.14);
    --text: #eef2ff;
    --text-soft: #c3cbda;
    --text-muted: #93a0b8;
    --line: rgba(255,255,255,0.08);
    --danger: #f87171;
    --good: #4ade80;
  }
  :global(html, body) { margin:0; min-height:100%; }
  :global(body) {
    font-family: 'Manrope', 'Segoe UI', sans-serif;
    background:
      radial-gradient(circle at top left, rgba(245,158,11,0.16) 0%, transparent 28%),
      radial-gradient(circle at top right, rgba(56,189,248,0.10) 0%, transparent 24%),
      linear-gradient(180deg, #08101b 0%, #0b1322 100%);
    color: var(--text);
  }
  :global(*) { box-sizing: border-box; }
  :global(a) { color: inherit; }
  :global(button), :global(input), :global(select), :global(textarea) {
    font: inherit;
  }
  :global(button) {
    border:1px solid rgba(255,255,255,0.12); background:#0c1424; color:var(--text);
    padding:0.65rem 0.9rem; border-radius:0.8rem; cursor:pointer;
  }
  :global(button:hover) { border-color: rgba(245,158,11,0.45); }
  :global(input), :global(select), :global(textarea) {
    width:100%; border:1px solid rgba(255,255,255,0.12); background:#0c1424; color:var(--text);
    padding:0.7rem 0.85rem; border-radius:0.8rem;
  }
  :global(table) { width:100%; border-collapse: collapse; }
  :global(th), :global(td) { padding:0.7rem 0.45rem; border-bottom:1px solid rgba(255,255,255,0.06); text-align:left; vertical-align:top; }
  :global(th) { color:var(--text-muted); font-size:0.78rem; text-transform:uppercase; letter-spacing:0.08rem; }
  .page { padding: 1.8rem; display:flex; flex-direction:column; gap:1.4rem; }
  @media (max-width: 900px) {
    .page { padding: 1rem; }
  }
</style>

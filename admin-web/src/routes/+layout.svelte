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
    --panel-strong: rgba(12, 17, 31, 0.94);
    --accent: #f59e0b;
    --accent-soft: rgba(245, 158, 11, 0.14);
    --text: #eef2ff;
    --text-soft: #c3cbda;
    --text-muted: #93a0b8;
    --line: rgba(255,255,255,0.08);
    --line-strong: rgba(255,255,255,0.14);
    --danger: #f87171;
    --danger-soft: rgba(248, 113, 113, 0.12);
    --good: #4ade80;
    --shadow-soft: 0 18px 40px rgba(2, 6, 23, 0.28);
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
    border:1px solid rgba(255,255,255,0.12);
    background:#0c1424;
    color:var(--text);
    padding:0.65rem 0.95rem;
    border-radius:0.8rem;
    cursor:pointer;
    font-weight: 600;
    transition: border-color 120ms ease, background 120ms ease, transform 120ms ease;
  }
  :global(button:hover) {
    border-color: rgba(245,158,11,0.45);
    transform: translateY(-1px);
  }
  :global(button.primary) {
    background: linear-gradient(135deg, rgba(245, 158, 11, 0.28), rgba(217, 119, 6, 0.22));
    border-color: rgba(245, 158, 11, 0.45);
  }
  :global(button.secondary) {
    background: rgba(255,255,255,0.03);
    color: var(--text-soft);
  }
  :global(button.ghost) {
    background: transparent;
    color: var(--text-muted);
    border-color: rgba(255,255,255,0.08);
  }
  :global(button.danger) {
    background: var(--danger-soft);
    border-color: rgba(248, 113, 113, 0.35);
    color: #fecaca;
  }
  :global(button:disabled) {
    opacity: 0.55;
    cursor: default;
    transform: none;
  }
  :global(input), :global(select), :global(textarea) {
    width:100%;
    border:1px solid rgba(255,255,255,0.12);
    background:#0c1424;
    color:var(--text);
    padding:0.7rem 0.85rem;
    border-radius:0.8rem;
  }
  :global(input:focus), :global(select:focus), :global(textarea:focus) {
    outline: none;
    border-color: rgba(245, 158, 11, 0.45);
    box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.08);
  }
  :global(table) { width:100%; border-collapse: collapse; }
  :global(th), :global(td) { padding:0.7rem 0.45rem; border-bottom:1px solid rgba(255,255,255,0.06); text-align:left; vertical-align:top; }
  :global(th) { color:var(--text-muted); font-size:0.78rem; text-transform:uppercase; letter-spacing:0.08rem; }
  .page {
    max-width: 1480px;
    margin: 0 auto;
    padding: 1.6rem 1.8rem 2rem;
    display:flex;
    flex-direction:column;
    gap:1.25rem;
  }
  @media (max-width: 900px) {
    .page { padding: 1rem; }
  }
</style>

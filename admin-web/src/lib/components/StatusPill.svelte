<script>
  export let mode = 'open';
  export let label = '';
</script>

<span class:open={mode === 'open'} class:acl={mode === 'acl'} class="pill">{label || mode}</span>

<style>
  .pill {
    display:inline-flex; align-items:center; gap:0.35rem; padding:0.22rem 0.65rem; border-radius:999px;
    font-size:0.72rem; letter-spacing:0.08rem; text-transform:uppercase; border:1px solid rgba(255,255,255,0.12);
  }
  .open { color:#86efac; border-color: rgba(34,197,94,0.35); background: rgba(34,197,94,0.12); }
  .acl { color:#fbbf24; border-color: rgba(245,158,11,0.35); background: rgba(245,158,11,0.12); }
</style>

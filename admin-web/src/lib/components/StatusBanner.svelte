<script>
  export let message = '';

  const classify = (value) => {
    const text = String(value || '').toLowerCase();
    if (!text.trim()) return 'info';
    if (text.includes('http 401') || text.includes('http 403') || text.includes('unauthorized') || text.includes('forbidden') || text.includes('failed') || text.includes('error') || text.includes('missing') || text.includes('invalid') || text.includes('expired')) {
      return 'error';
    }
    if (text.includes('loading') || text.includes('refreshing') || text.includes('creating') || text.includes('saving') || text.includes('deleting') || text.includes('compacting') || text.includes('rebuilding') || text.includes('importing') || text.includes('exporting')) {
      return 'working';
    }
    return 'info';
  };

  $: tone = classify(message);
</script>

{#if message}
  <div class:tone-error={tone === 'error'} class:tone-working={tone === 'working'} class="banner" role={tone === 'error' ? 'alert' : 'status'}>
    <strong>{tone === 'error' ? 'Attention' : tone === 'working' ? 'Working' : 'Status'}</strong>
    <span>{message}</span>
  </div>
{/if}

<style>
  .banner {
    display: grid;
    gap: 0.2rem;
    padding: 0.95rem 1rem;
    border-radius: 1rem;
    border: 1px solid rgba(255, 255, 255, 0.08);
    background: rgba(255, 255, 255, 0.04);
    color: var(--text);
  }
  .banner strong {
    font-size: 0.8rem;
    letter-spacing: 0.08rem;
    text-transform: uppercase;
    color: var(--text-muted);
  }
  .banner span {
    color: var(--text-soft);
  }
  .banner.tone-error {
    border-color: rgba(239, 68, 68, 0.45);
    background: rgba(127, 29, 29, 0.28);
  }
  .banner.tone-error strong,
  .banner.tone-error span {
    color: #fecaca;
  }
  .banner.tone-working {
    border-color: rgba(245, 158, 11, 0.35);
    background: rgba(120, 53, 15, 0.22);
  }
  .banner.tone-working strong,
  .banner.tone-working span {
    color: #fde68a;
  }
</style>

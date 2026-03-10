<div class="split-view">
  <aside class="sidebar">
    <slot name="sidebar" />
  </aside>
  <section class="detail">
    <slot />
  </section>
</div>

<style>
  .split-view {
    display: grid;
    grid-template-columns: minmax(290px, 360px) minmax(0, 1fr);
    gap: 1rem;
    align-items: start;
  }

  .sidebar,
  .detail {
    min-width: 0;
  }

  .detail {
    display: grid;
    gap: 1rem;
  }

  @media (max-width: 1080px) {
    .split-view {
      grid-template-columns: 1fr;
    }
  }
</style>

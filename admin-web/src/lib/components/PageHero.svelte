<script>
  export let eyebrow = '';
  export let title = '';
  export let description = '';
</script>

<section class="hero">
  <div>
    {#if eyebrow}<div class="eyebrow">{eyebrow}</div>{/if}
    <h1>{title}</h1>
    {#if description}<p>{description}</p>{/if}
  </div>
  <div class="actions">
    <slot />
  </div>
</section>

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

  .actions {
    display: flex;
    gap: 0.7rem;
    flex-wrap: wrap;
  }

  @media (max-width: 1000px) {
    .hero {
      grid-template-columns: 1fr;
    }
  }
</style>

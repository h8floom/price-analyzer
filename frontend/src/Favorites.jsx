export function FavoriteButton({ product, saved, disabled, onToggle }) {
  return <button type="button" className="personal-icon favorite-button"
    aria-label={`Избранное: ${product.name}`} aria-pressed={saved}
    disabled={disabled} onClick={() => onToggle(product.id, !saved)}>
    <svg aria-hidden="true" viewBox="0 0 24 24" fill={saved ? 'currentColor' : 'none'}
      stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round">
      <path d="M12 20.2 4.2 12.6a4.8 4.8 0 0 1 6.8-6.8l1 1 1-1a4.8 4.8 0 0 1 6.8 6.8L12 20.2Z" />
    </svg>
  </button>;
}

export default function Favorites({ products, favorites, busy, onToggle, onCompare, onCatalog, loadError, retry }) {
  if (loadError) return <section className="panel personal-empty">
    <p role="alert">{loadError}</p>
    <button className="btn secondary personal-control" onClick={retry}>Повторить</button>
  </section>;
  if (favorites === null) return <p role="status">Загрузка избранного…</p>;
  const saved = products.filter(({ id }) => favorites.has(id));
  if (!saved.length) return <section className="panel personal-empty">
    <h2>Избранное пока пусто</h2>
    <p>Отметьте сердечком товар в каталоге, чтобы следить за его ценой.</p>
    <button className="btn secondary personal-control" onClick={onCatalog}>Открыть каталог</button>
  </section>;
  return <ul className="favorite-grid">
    {saved.map((product) => <li key={product.id} className="favorite-card">
      <div>
        <h2>{product.name}</h2>
        <p className="favorite-meta">{product.category} / {product.unit}</p>
        {product.description && <p>{product.description}</p>}
      </div>
      <FavoriteButton product={product} saved disabled={busy.has(product.id)} onToggle={onToggle} />
      <button className="btn secondary personal-control" onClick={() => onCompare(product.id)}>Сравнить цены</button>
    </li>)}
  </ul>;
}

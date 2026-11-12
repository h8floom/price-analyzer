import { useEffect, useMemo, useState } from 'react';
import { formatPrice } from './lib/formatPrice.js';
import { calculatePriceStats, getLatestPricesByStore, getProductPrices } from './lib/priceUtils.js';
import './App.css';

const STORAGE_KEY = 'price-analyzer-v1';

const initialProducts = [
  { id: 1, name: 'Кофе молотый, 250 г', category: 'Напитки', unit: 'упаковка' },
  { id: 2, name: 'Чай чёрный, 100 пакетиков', category: 'Напитки', unit: 'упаковка' },
  { id: 3, name: 'Шоколад молочный, 90 г', category: 'Сладости', unit: 'плитка' },
  { id: 4, name: 'Молоко 2,5%, 1 л', category: 'Молочные продукты', unit: 'литр' },
];

const initialPrices = [
  { id: 1, productId: 1, store: 'Магнит', price: 369, date: '2026-10-03' },
  { id: 2, productId: 1, store: 'Пятёрочка', price: 379, date: '2026-10-03' },
  { id: 3, productId: 1, store: 'Лента', price: 349, date: '2026-10-03' },
  { id: 4, productId: 1, store: 'Магнит', price: 359, date: '2026-10-10' },
  { id: 5, productId: 1, store: 'Пятёрочка', price: 369, date: '2026-10-10' },
  { id: 6, productId: 1, store: 'Лента', price: 339, date: '2026-10-10' },
  { id: 7, productId: 1, store: 'Магнит', price: 349, date: '2026-10-17' },
  { id: 8, productId: 1, store: 'Пятёрочка', price: 365, date: '2026-10-17' },
  { id: 9, productId: 1, store: 'Лента', price: 345, date: '2026-10-17' },
  { id: 10, productId: 1, store: 'Магнит', price: 355, date: '2026-10-24' },
  { id: 11, productId: 1, store: 'Пятёрочка', price: 359, date: '2026-10-24' },
  { id: 12, productId: 1, store: 'Лента', price: 339, date: '2026-10-24' },
  { id: 13, productId: 2, store: 'Магнит', price: 189, date: '2026-10-03' },
  { id: 14, productId: 2, store: 'Пятёрочка', price: 205, date: '2026-10-03' },
  { id: 15, productId: 2, store: 'Лента', price: 199, date: '2026-10-03' },
  { id: 16, productId: 2, store: 'Магнит', price: 195, date: '2026-10-17' },
  { id: 17, productId: 2, store: 'Пятёрочка', price: 199, date: '2026-10-17' },
  { id: 18, productId: 2, store: 'Лента', price: 189, date: '2026-10-17' },
  { id: 19, productId: 3, store: 'Магнит', price: 119, date: '2026-10-10' },
  { id: 20, productId: 3, store: 'Пятёрочка', price: 125, date: '2026-10-10' },
  { id: 21, productId: 3, store: 'Лента', price: 115, date: '2026-10-10' },
  { id: 22, productId: 4, store: 'Магнит', price: 89, date: '2026-10-17' },
  { id: 23, productId: 4, store: 'Пятёрочка', price: 92, date: '2026-10-17' },
  { id: 24, productId: 4, store: 'Лента', price: 85, date: '2026-10-17' },
];

const navItems = [
  { id: 'overview', label: 'Обзор', icon: 'grid' },
  { id: 'catalog', label: 'Каталог товаров', icon: 'list' },
  { id: 'compare', label: 'Сравнение цен', icon: 'swap' },
];

function loadInitialData() {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      if (Array.isArray(parsed.products) && Array.isArray(parsed.prices)) return parsed;
    }
  } catch {
    // если локальное хранилище недоступно, используем демонстрационные данные.
  }
  return { products: initialProducts, prices: initialPrices };
}

function formatDate(date) {
  return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' }).format(new Date(`${date}T00:00:00`));
}

function todayIso() {
  const now = new Date();
  const offset = now.getTimezoneOffset();
  return new Date(now.getTime() - offset * 60_000).toISOString().slice(0, 10);
}

function Icon({ name }) {
  const icons = {
    grid: (
      <>
        <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
        <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
        <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
        <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
      </>
    ),
    list: <path d="M4 6h16M4 12h16M4 18h16" />,
    swap: (
      <>
        <path d="M7 4 3 8l4 4" />
        <path d="M3 8h14" />
        <path d="m17 20 4-4-4-4" />
        <path d="M21 16H7" />
      </>
    ),
    search: (
      <>
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </>
    ),
    inbox: (
      <>
        <path d="M3 12h5l2 3h4l2-3h5" />
        <path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" />
      </>
    ),
  };
  return (
    <svg className="icon-svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {icons[name]}
    </svg>
  );
}

function PriceChart({ records }) {
  const [hovered, setHovered] = useState(null);
  const chart = useMemo(() => {
    const rows = [...records].sort((a, b) => a.date.localeCompare(b.date));
    const dates = [...new Set(rows.map((row) => row.date))];
    const stores = [...new Set(rows.map((row) => row.store))];
    const values = rows.map((row) => row.price);
    const min = Math.floor(Math.min(...values) / 20) * 20;
    const maxValue = Math.max(...values, min + 20);
    const max = Math.ceil(maxValue / 20) * 20;
    const width = 680;
    const height = 260;
    const left = 54;
    const right = 24;
    const top = 18;
    const bottom = 38;
    const x = (index) => dates.length === 1 ? left + (width - left - right) / 2 : left + (index / (dates.length - 1)) * (width - left - right);
    const y = (value) => height - bottom - ((value - min) / Math.max(1, max - min)) * (height - top - bottom);
    const palette = ['#4a90d9', '#12a78b', '#e99a36', '#cb6eaa'];
    const lines = stores.map((store, storeIndex) => {
      const points = dates.map((date, index) => {
        const row = rows.find((item) => item.store === store && item.date === date);
        return row ? { x: x(index), y: y(row.price), date, price: row.price } : null;
      }).filter(Boolean);
      return { store, color: palette[storeIndex % palette.length], points };
    });
    return { dates, min, max, width, height, left, right, top, bottom, x, y, lines };
  }, [records]);

  if (!records.length) return <div className="empty-chart">Для выбранного товара пока нет записей о ценах.</div>;

  const ticks = Array.from({ length: 5 }, (_, index) => chart.min + ((chart.max - chart.min) / 4) * index);
  return (
    <div className="chart-wrap">
      <svg className="price-chart" viewBox={`0 0 ${chart.width} ${chart.height}`} role="img" aria-label="График изменения цены по магазинам">
        {ticks.map((tick) => (
          <g key={tick}>
            <line x1={chart.left} y1={chart.y(tick)} x2={chart.width - chart.right} y2={chart.y(tick)} stroke="#e9edf4" strokeDasharray="4 5" />
            <text x={chart.left - 10} y={chart.y(tick) + 4} textAnchor="end" className="axis-label">{Math.round(tick)} ₽</text>
          </g>
        ))}
        {chart.dates.map((date, index) => (
          <text key={date} x={chart.x(index)} y={chart.height - 12} textAnchor="middle" className="axis-label">{formatDate(date)}</text>
        ))}
        {chart.lines.map((line) => (
          <g key={line.store}>
            {line.points.length > 1 && <polyline points={line.points.map((point) => `${point.x},${point.y}`).join(' ')} fill="none" stroke={line.color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />}
            {line.points.map((point) => {
              const key = `${line.store}-${point.date}`;
              const isHovered = hovered?.key === key;
              return (
                <circle
                  key={key}
                  cx={point.x}
                  cy={point.y}
                  r={isHovered ? 6 : 4.5}
                  fill={line.color}
                  stroke="white"
                  strokeWidth="2"
                  style={{ cursor: 'pointer' }}
                  onMouseEnter={() => setHovered({ key, x: point.x, y: point.y, store: line.store, price: point.price, date: point.date })}
                  onMouseLeave={() => setHovered(null)}
                />
              );
            })}
          </g>
        ))}
        {hovered && (() => {
          const tw = 150;
          const th = 44;
          const tx = Math.min(Math.max(hovered.x, tw / 2 + 8), chart.width - tw / 2 - 8);
          const placeBelow = hovered.y < th + 20;
          const ty = placeBelow ? hovered.y + 10 : hovered.y - th - 10;
          return (
            <g pointerEvents="none">
              <line x1={hovered.x} y1={hovered.y - 4} x2={hovered.x} y2={placeBelow ? ty : ty + th} stroke="#c8cbd5" strokeDasharray="3 3" />
              <rect x={tx - tw / 2} y={ty} width={tw} height={th} rx={6} fill="#1e2436" />
              <text x={tx} y={ty + 18} textAnchor="middle" fill="#ffffff" fontSize="10" fontWeight="600">{hovered.store}</text>
              <text x={tx} y={ty + 33} textAnchor="middle" fill="#c8cbd5" fontSize="9">{formatPrice(hovered.price)} · {formatDate(hovered.date)}</text>
            </g>
          );
        })()}
      </svg>
      <div className="chart-legend">
        {chart.lines.map((line) => <span key={line.store}><i style={{ background: line.color }} />{line.store}</span>)}
      </div>
    </div>
  );
}

function EmptyState({ title, description }) {
  return <div className="empty-state"><div className="empty-icon"><Icon name="inbox" /></div><strong>{title}</strong><p>{description}</p></div>;
}

export default function App() {
  const [data, setData] = useState(loadInitialData);
  const [activePage, setActivePage] = useState('overview');
  const [selectedProductId, setSelectedProductId] = useState('1');
  const [showProductForm, setShowProductForm] = useState(false);
  const [showPriceForm, setShowPriceForm] = useState(false);
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState('');
  const [productForm, setProductForm] = useState({ name: '', category: 'Продукты', unit: 'шт.' });
  const [priceForm, setPriceForm] = useState({ productId: '1', store: 'Магнит', price: '', date: todayIso() });
  const [formError, setFormError] = useState('');

  useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch {
      // приложение продолжает работать в текущем сеансе даже без localStorage.
    }
  }, [data]);

  const selectedProduct = data.products.find((product) => String(product.id) === String(selectedProductId)) || data.products[0];
  const productRecords = selectedProduct ? getProductPrices(data.prices, selectedProduct.id) : [];
  const latestPrices = selectedProduct ? getLatestPricesByStore(data.prices, selectedProduct.id) : [];
  const stats = calculatePriceStats(latestPrices);
  const filteredProducts = data.products.filter((product) => `${product.name} ${product.category}`.toLowerCase().includes(search.toLowerCase()));
  const latestRecord = [...data.prices].sort((a, b) => b.date.localeCompare(a.date))[0];
  const globalAverage = data.prices.length ? data.prices.reduce((sum, row) => sum + row.price, 0) / data.prices.length : 0;

  function selectPage(page) {
    setActivePage(page);
    setNotice('');
    setFormError('');
  }

  function addProduct(event) {
    event.preventDefault();
    const name = productForm.name.trim();
    if (!name) {
      setFormError('Укажи название товара.');
      return;
    }
    if (data.products.some((product) => product.name.toLowerCase() === name.toLowerCase())) {
      setFormError('Товар с таким названием уже есть в каталоге.');
      return;
    }
    const id = Math.max(0, ...data.products.map((product) => Number(product.id))) + 1;
    setData((current) => ({ ...current, products: [...current.products, { id, ...productForm, name }] }));
    setSelectedProductId(String(id));
    setPriceForm((current) => ({ ...current, productId: String(id) }));
    setProductForm({ name: '', category: 'Продукты', unit: 'шт.' });
    setShowProductForm(false);
    setFormError('');
    setNotice('Товар добавлен в каталог.');
  }

  function addPrice(event) {
    event.preventDefault();
    const price = Number(priceForm.price);
    if (!priceForm.productId || !priceForm.store.trim() || !priceForm.date || !Number.isFinite(price) || price <= 0) {
      setFormError('Заполни товар, магазин, дату и положительную цену.');
      return;
    }
    const duplicate = data.prices.some((row) => String(row.productId) === String(priceForm.productId) && row.store.trim().toLowerCase() === priceForm.store.trim().toLowerCase() && row.date === priceForm.date);
    if (duplicate) {
      setFormError('Для этого товара, магазина и даты запись уже существует.');
      return;
    }
    const id = Math.max(0, ...data.prices.map((row) => Number(row.id))) + 1;
    setData((current) => ({ ...current, prices: [...current.prices, { id, ...priceForm, productId: Number(priceForm.productId), store: priceForm.store.trim(), price }] }));
    setSelectedProductId(String(priceForm.productId));
    setActivePage('overview');
    setShowPriceForm(false);
    setPriceForm((current) => ({ ...current, price: '', date: todayIso() }));
    setFormError('');
    setNotice('Цена добавлена. График и сравнение обновлены.');
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark">P</div>
          <div><b>PricePilot</b><span>Анализ цен</span></div>
        </div>
        <div className="sidebar-label">РАБОЧАЯ ОБЛАСТЬ</div>
        <nav className="side-nav" aria-label="Основная навигация">
          {navItems.map((item) => (
            <button key={item.id} className={`nav-item ${activePage === item.id ? 'active' : ''}`} onClick={() => selectPage(item.id)}>
              <Icon name={item.icon} /><span>{item.label}</span>{activePage === item.id && <i />}
            </button>
          ))}
        </nav>
      </aside>

      <main className="main-area">
        <header className="topbar">
          <div className="breadcrumb">Рабочая область <span>/</span> <b>{navItems.find((item) => item.id === activePage)?.label}</b></div>
        </header>

        <div className="content-area">
          <div className="page-heading">
            <div><p className="overline">PRICE ANALYTICS</p><h1>{activePage === 'overview' ? 'Обзор цен' : activePage === 'catalog' ? 'Каталог товаров' : 'Сравнение цен'}</h1><p className="page-description">{activePage === 'overview' ? 'Следи за изменением стоимости и находи лучшие предложения.' : activePage === 'catalog' ? 'Список товаров, для которых мы собираем информацию о ценах.' : 'Сравни последние цены в разных магазинах и выбирай выгодное предложение.'}</p></div>
            <button className="primary-button" onClick={() => { setShowPriceForm(true); setFormError(''); setPriceForm((current) => ({ ...current, productId: selectedProduct ? String(selectedProduct.id) : String(data.products[0]?.id || '') })); }}><span>＋</span> Добавить цену</button>
          </div>

          {notice && <div className="notice" role="status"><span>✓</span>{notice}<button onClick={() => setNotice('')} aria-label="Закрыть сообщение">×</button></div>}

          {activePage === 'overview' && (
            <>
              <section className="metric-grid" aria-label="Основные показатели">
                <article className="metric-card"><div className="metric-top"><span>Товаров в каталоге</span></div><div className="metric-value">{data.products.length}</div></article>
                <article className="metric-card"><div className="metric-top"><span>Записей о ценах</span></div><div className="metric-value">{data.prices.length}</div></article>
                <article className="metric-card"><div className="metric-top"><span>Средняя цена записи</span></div><div className="metric-value">{formatPrice(globalAverage)}</div></article>
                <article className="metric-card"><div className="metric-top"><span>Последнее обновление</span></div><div className="metric-value date-value">{latestRecord ? formatDate(latestRecord.date) : '—'}</div></article>
              </section>

              <section className="panel chart-panel">
                <div className="panel-heading"><div><h2>Динамика цен</h2><p>История изменения стоимости по магазинам</p></div><label className="select-label"><span>Товар</span><select value={selectedProduct ? selectedProduct.id : ''} onChange={(event) => setSelectedProductId(event.target.value)} aria-label="Товар для графика">{data.products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}</select></label></div>
                {selectedProduct && <div className="chart-summary"><strong>{selectedProduct.name}</strong><span>{productRecords.length} замеров цены</span></div>}
                <PriceChart records={productRecords} />
              </section>

              <section className="panel recent-panel">
                <div className="panel-heading"><div><h2>Сравнение по магазинам</h2><p>Самая свежая цена в каждом магазине для выбранного товара</p></div><button className="text-button" onClick={() => selectPage('compare')}>Подробнее <span></span></button></div>
                {latestPrices.length ? <div className="comparison-list">{latestPrices.map((record, index) => <div className={`comparison-row ${index === 0 ? 'best-row' : ''}`} key={`${record.store}-${record.date}`}><div className="store-avatar">{record.store.slice(0, 1)}</div><div className="store-info"><b>{record.store}</b><span>Обновлено {formatDate(record.date)}</span></div><div className="price-track"><div style={{ width: `${Math.max(12, (record.price / Math.max(...latestPrices.map((item) => item.price))) * 100)}%` }} /></div><strong className="store-price">{formatPrice(record.price)}</strong>{index === 0 ? <span className="best-label">Выгодно</span> : <span className="neutral-label">—</span>}</div>)}</div> : <EmptyState title="Нет цен для сравнения" description="Добавь первую запись о цене для выбранного товара." />}
              </section>
            </>
          )}

          {activePage === 'catalog' && (
            <section className="panel catalog-panel">
              <div className="panel-heading catalog-heading"><div><h2>Все товары</h2><p>{data.products.length} позиций в каталоге</p></div><button className="secondary-button" onClick={() => { setShowProductForm((open) => !open); setFormError(''); }}><span>＋</span> Новый товар</button></div>
              {showProductForm && <form className="inline-form product-form" onSubmit={addProduct}><label>Название товара<input autoFocus value={productForm.name} onChange={(event) => setProductForm({ ...productForm, name: event.target.value })} placeholder="Например, Овсяные хлопья, 500 г" /></label><label>Категория<select value={productForm.category} onChange={(event) => setProductForm({ ...productForm, category: event.target.value })}><option>Продукты</option><option>Напитки</option><option>Сладости</option><option>Молочные продукты</option><option>Бытовые товары</option></select></label><label>Единица<input value={productForm.unit} onChange={(event) => setProductForm({ ...productForm, unit: event.target.value })} placeholder="шт." /></label><div className="form-actions"><button type="button" className="cancel-button" onClick={() => setShowProductForm(false)}>Отмена</button><button type="submit" className="primary-button">Сохранить</button></div></form>}
              <div className="table-toolbar"><div className="search-box"><Icon name="search" /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск по названию или категории..." aria-label="Поиск товаров" /></div><span className="muted-text">Сначала новые изменения</span></div>
              {filteredProducts.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>ТОВАР</th><th>КАТЕГОРИЯ</th><th>ЗАМЕРОВ</th><th>МИН. ЦЕНА</th><th>ДЕЙСТВИЕ</th></tr></thead><tbody>{filteredProducts.map((product) => { const rows = getLatestPricesByStore(data.prices, product.id); const productStats = calculatePriceStats(rows); return <tr key={product.id}><td><div className="product-cell"><div className="product-icon">{product.name.slice(0, 1)}</div><div><b>{product.name}</b><span>{product.unit}</span></div></div></td><td><span className="category-chip">{product.category}</span></td><td>{getProductPrices(data.prices, product.id).length}</td><td><b>{productStats.min == null ? 'Нет данных' : formatPrice(productStats.min)}</b></td><td><button className="table-action" onClick={() => { setSelectedProductId(String(product.id)); selectPage('compare'); }}>Сравнить</button></td></tr>; })}</tbody></table></div> : <EmptyState title="Ничего не найдено" description="Попробуй изменить поисковый запрос." />}
            </section>
          )}

          {activePage === 'compare' && (
            <>
              <section className="panel compare-selector"><div><h2>Выбери товар</h2><p>Цены отсортированы от низкой к высокой.</p></div><select value={selectedProduct ? selectedProduct.id : ''} onChange={(event) => setSelectedProductId(event.target.value)} aria-label="Товар для сравнения">{data.products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}</select></section>
              {selectedProduct ? <><section className="metric-grid comparison-metrics"><article className="metric-card"><div className="metric-top"><span>Минимальная</span></div><div className="metric-value">{stats.min == null ? '—' : formatPrice(stats.min)}</div></article><article className="metric-card"><div className="metric-top"><span>Средняя по магазинам</span></div><div className="metric-value">{stats.average == null ? '—' : formatPrice(stats.average)}</div></article><article className="metric-card"><div className="metric-top"><span>Максимальная</span></div><div className="metric-value">{stats.max == null ? '—' : formatPrice(stats.max)}</div></article></section>
                <section className="panel compare-table-panel"><div className="panel-heading"><div><h2>Цены в магазинах</h2><p>{selectedProduct.name}</p></div><span className="rows-count">{latestPrices.length} магазина</span></div>{latestPrices.length ? <div className="table-scroll"><table className="data-table"><thead><tr><th>МАГАЗИН</th><th>ПОСЛЕДНЯЯ ЦЕНА</th><th>ДАТА</th><th>РАЗНИЦА С МИНИМУМОМ</th></tr></thead><tbody>{latestPrices.map((record, index) => <tr key={record.store} className={index === 0 ? 'highlight-row' : ''}><td><div className="store-table-cell"><div className="store-avatar">{record.store.slice(0, 1)}</div><b>{record.store}</b>{index === 0 && <span className="best-label">Лучшая цена</span>}</div></td><td><strong className={index === 0 ? 'green-price' : ''}>{formatPrice(record.price)}</strong></td><td>{formatDate(record.date)}</td><td>{index === 0 ? '—' : `+${formatPrice(record.price - (stats.min ?? record.price))}`}</td></tr>)}</tbody></table></div> : <EmptyState title="Нет данных для сравнения" description="Добавь цену товара хотя бы в одном магазине." />}</section></> : <EmptyState title="Каталог пуст" description="Сначала добавь товар в каталог." />}
            </>
          )}

          <footer className="page-footer"><span></span></footer>
        </div>
      </main>

      {showPriceForm && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowPriceForm(false); }}><section className="modal-card" role="dialog" aria-modal="true" aria-labelledby="price-modal-title"><div className="modal-heading"><div><p className="overline">НОВАЯ ЗАПИСЬ</p><h2 id="price-modal-title">Добавить цену</h2><p>Укажи стоимость товара в конкретном магазине.</p></div><button className="close-button" onClick={() => setShowPriceForm(false)} aria-label="Закрыть">×</button></div><form className="modal-form" onSubmit={addPrice}><label>Товар<select value={priceForm.productId} onChange={(event) => setPriceForm({ ...priceForm, productId: event.target.value })} required>{data.products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}</select></label><label>Магазин<input value={priceForm.store} onChange={(event) => setPriceForm({ ...priceForm, store: event.target.value })} placeholder="Например, Магнит" required /></label><div className="form-two-col"><label>Цена, ₽<input type="number" min="0.01" step="0.01" value={priceForm.price} onChange={(event) => setPriceForm({ ...priceForm, price: event.target.value })} placeholder="0,00" required /></label><label>Дата замера<input type="date" value={priceForm.date} onChange={(event) => setPriceForm({ ...priceForm, date: event.target.value })} required /></label></div>{formError && <p className="form-error" role="alert">{formError}</p>}<p className="form-hint">Повторная запись для того же товара, магазина и даты не допускается.</p><div className="form-actions"><button type="button" className="cancel-button" onClick={() => setShowPriceForm(false)}>Отмена</button><button type="submit" className="primary-button">Сохранить цену</button></div></form></section></div>}
    </div>
  );
}
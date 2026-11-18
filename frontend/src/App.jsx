import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from './lib/api.js';
import AccountForm from './AccountForm.jsx';
import Reports from './Reports.jsx';
import AdminSettings from './AdminSettings.jsx';
import Favorites, { FavoriteButton } from './Favorites.jsx';
import Notifications, { NotificationBell, useUnreadNotifications } from './Notifications.jsx';
import { calculateStats, dateLabel, formatPrice, getLatestByStore, getSeries } from './lib/priceUtils.js';

const NAV = [
  ['overview', '▦', 'Обзор'],
  ['catalog', '▤', 'Каталог товаров'],
  ['favorites', '♡', 'Избранное'],
  ['compare', '↗', 'Сравнение цен'],
  ['import', '⇪', 'Импорт / экспорт'],
  ['reports', '▥', 'Отчёты'],
  ['settings', '⚙', 'Управление'],
  ['notifications', '', 'Уведомления'],
];

function LineChart({ history }) {
  const [activeId, setActiveId] = useState(null);
  const { dates, stores } = useMemo(() => getSeries(history), [history]);
  if (!history.length) return <div className="empty">Для выбранного периода пока нет цен.</div>;
  const colors = ['#7367f0', '#13ae99', '#f0a34e', '#d86cad', '#4b92d4'];
  const lower = history.reduce((min, point) => Math.min(min, Number(point.price)), Infinity);
  const upper = history.reduce((max, point) => Math.max(max, Number(point.price)), 0);
  const min = Math.floor(lower / 20) * 20;
  const max = Math.ceil(Math.max(upper, min + 20) / 20) * 20;
  const start = Date.parse(dates[0]);
  const end = Date.parse(dates.at(-1));
  const step = Math.ceil(dates.length / 6);
  const active = history.find((point) => point.id === activeId);
  const coords = (date, price) => [
    60 + (dates.length === 1 ? 310 : (Date.parse(date) - start) * 620 / (end - start)),
    240 - (Number(price) - min) * 190 / (max - min),
  ];
  const lastDate = dates.at(-1);
  const lastX = coords(lastDate, min)[0];
  const labelGap = 75;
  const labels = [];
  let previousX = -Infinity;
  for (let index = 0; index < dates.length - 1; index += step) {
    const date = dates[index];
    const [x] = coords(date, min);
    if (x - previousX >= labelGap && lastX - x >= labelGap) {
      labels.push(date);
      previousX = x;
    }
  }
  labels.push(lastDate);
  const ticks = [0, 1, 2, 3, 4].map((x) => min + x * (max - min) / 4);
  return <div className="chart-wrap">
    <svg viewBox="0 0 720 280" role="img" aria-label="Линейный график динамики цен">
      {ticks.map((v) => <g key={v}>
        <line x1="60" x2="680" y1={coords(dates[0], v)[1]} y2={coords(dates[0], v)[1]} stroke="#e8ecf5" strokeDasharray="4 6" />
        <text x="48" y={coords(dates[0], v)[1] + 4} textAnchor="end" className="axis">{Math.round(v)} ₽</text>
      </g>)}
      {labels.map((d) => <text key={d} x={coords(d, min)[0]} y="264" textAnchor="middle" className="axis">{dateLabel(d)}</text>)}
      {stores.map((store, index) => {
        const key = store.toLowerCase();
        const points = history.filter((p) => p.store.toLowerCase() === key).sort((a, b) => a.date.localeCompare(b.date));
        const line = points.map((p) => coords(p.date, p.price).join(',')).join(' ');
        return <g key={store}>
          {points.length > 1 && <polyline points={line} stroke={colors[index % colors.length]} fill="none" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />}
          {points.map((p) => {
            const [x, y] = coords(p.date, p.price);
            return <circle key={p.id} cx={x} cy={y} r={activeId === p.id ? 7 : 5} fill={colors[index % colors.length]}
              stroke="white" strokeWidth="2" tabIndex={0}
              aria-label={`${store}: ${formatPrice(p.price)} — ${p.date}`}
              onFocus={() => setActiveId(p.id)} onBlur={() => setActiveId(null)}
              onMouseEnter={() => setActiveId(p.id)} onMouseLeave={() => setActiveId(null)}>
              <title>{`${store}: ${formatPrice(p.price)} — ${p.date}`}</title>
            </circle>;
          })}
        </g>;
      })}
    </svg>
    <p className="chart-probe" role="status">{active && `${active.store}: ${formatPrice(active.price)} — ${active.date}`}</p>
    <div className="legend">{stores.map((s, i) => <span key={s}><i style={{ background: colors[i % colors.length] }} />{s}</span>)}</div>
  </div>;
}

function DateRange({ from, to, setFrom, setTo }) {
  return <div className="date-filter">
    <label>Начало периода<input type="date" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
    <label>Конец периода<input type="date" value={to} onChange={(event) => setTo(event.target.value)} /></label>
    <button type="button" onClick={() => { setFrom(''); setTo(''); }} className="text-btn">Сбросить период</button>
  </div>;
}

function Metric({ title, value, caption, icon }) {
  return <div className="metric"><div className="metric-title">{title}<span className="metric-icon">{icon}</span></div><strong>{value}</strong><small>{caption}</small></div>;
}

function ProductForm({ initial, onCancel, onSave }) {
  const [form, setForm] = useState(initial || { name: '', category: 'Продукты', unit: 'шт.', description: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    setBusy(true); setError('');
    try { await onSave(form); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  return <form className="form-grid" onSubmit={submit}>
    <label>Название<input required maxLength="240" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Например, Кофе молотый, 250 г" /></label>
    <label>Категория<input required maxLength="120" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} /></label>
    <label>Единица<input required maxLength="64" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} /></label>
    <label>Описание<textarea maxLength={2000} rows={3} value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
    {error && <div className="field-error">{error}</div>}
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button><button className="btn primary" disabled={busy} type="submit">{busy ? 'Сохранение...' : 'Сохранить товар'}</button></div>
  </form>;
}

function todayLocal() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

function PriceForm({ products, selectedId, onCancel, onSave }) {
  const [form, setForm] = useState({ productId: Number(selectedId || products[0]?.id),
    store: 'Магнит', price: '', date: todayLocal() });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault(); setBusy(true); setError('');
    try { await onSave({ ...form, productId: Number(form.productId), price: Number(form.price) }); }
    catch (e) { setError(e.message); }
    finally { setBusy(false); }
  }
  return <form className="form-grid" onSubmit={submit}>
    <label>Товар<select required value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    <label>Магазин<input required maxLength="160" value={form.store} onChange={(e) => setForm({ ...form, store: e.target.value })} /></label>
    <div className="two-col">
      <label>Цена, ₽<input required type="number" min="0.01" step="0.01" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} /></label>
      <label>Дата<input required type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></label>
    </div>
    {error && <div className="field-error">{error}</div>}
    <p className="helper">Если цена за эту дату уже есть, измените последнее наблюдение в сравнении цен.</p>
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button><button disabled={busy} className="btn primary" type="submit">{busy ? 'Сохранение...' : 'Сохранить цену'}</button></div>
  </form>;
}

function PriceUpdateForm({ record, onSave, onCancel }) {
  const [value, setValue] = useState(record.price);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try { await onSave(Number(value)); }
    catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  }

  return <form className="form-grid" onSubmit={save}>
    <p className="helper">{record.store} · {record.date}. Предыдущая цена останется в истории.</p>
    <label>Новая цена, ₽<input name="price" required type="number" min="0.01" max="9999999999.99"
      step="0.01" value={value} onChange={(event) => setValue(event.target.value)} /></label>
    {error && <div role="alert" className="field-error">{error}</div>}
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button>
      <button className="btn primary" disabled={busy}>{busy ? 'Сохранение…' : 'Сохранить цену'}</button></div>
  </form>;
}

function Modal({ label, onClose, children }) {
  const ref = useRef(null);
  useEffect(() => {
    const dialog = ref.current;
    dialog.showModal();
    return () => dialog.close();
  }, []);
  return <dialog ref={ref} className="dialog" aria-label={label} onCancel={(event) => {
    event.preventDefault();
    onClose();
  }}>{children}</dialog>;
}

function Workspace({ session, onLogout }) {
  const admin = session.role === 'ADMIN';
  const [page, setPage] = useState('overview');
  const [products, setProducts] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [analysisResult, setAnalysisResult] = useState(null);
  const [allPrices, setAllPrices] = useState([]);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [modal, setModal] = useState('');
  const [editing, setEditing] = useState(null);
  const [editingPrice, setEditingPrice] = useState(null);
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [tick, setTick] = useState(0);
  const [busyImport, setBusyImport] = useState(false);
  const [exportFilter, setExportFilter] = useState({ productId: '', from: '', to: '' });
  const [favorites, setFavorites] = useState(null);
  const [busyFavorites, setBusyFavorites] = useState(() => new Set());
  const [favoriteError, setFavoriteError] = useState('');
  const [favoriteAttempt, setFavoriteAttempt] = useState(0);
  const inbox = useUnreadNotifications();
  const analysisKey = JSON.stringify([selectedId, from, to, tick]);

  useEffect(() => {
    function productLink() {
      const id = new URLSearchParams(location.hash.slice(1)).get('product');
      if (id && /^[1-9]\d*$/.test(id)) {
        setSelectedId(id);
        setFrom('');
        setTo('');
        setPage('compare');
      }
    }
    window.addEventListener('hashchange', productLink);
    productLink();
    return () => window.removeEventListener('hashchange', productLink);
  }, []);

  useEffect(() => {
    const load = new AbortController();
    api.favorites(load.signal).then((ids) => setFavorites(new Set(ids)))
      .catch((failure) => { if (!load.signal.aborted) setFavoriteError(failure.message); });
    return () => load.abort();
  }, [favoriteAttempt]);

  useEffect(() => {
    let ignore = false;
    Promise.all([api.health(), api.products(), api.prices()])
      .then(([_, list, prices]) => {
        if (ignore) return;
        setProducts(list);
        setAllPrices(prices);
        setSelectedId((old) => list.some((p) => String(p.id) === String(old))
          ? old : String(list[0]?.id || ''));
        setError('');
      })
      .catch((e) => { if (!ignore) setError(e.message); });
    return () => { ignore = true; };
  }, [tick]);

  useEffect(() => {
    if (!selectedId) return;
    const request = new AbortController();
    api.analysis(selectedId, from, to, request.signal)
      .then((value) => {
        if (!request.signal.aborted) setAnalysisResult({ key: analysisKey, value });
      })
      .catch((failure) => {
        if (!request.signal.aborted) setAnalysisResult({ key: analysisKey, error: failure.message });
      });
    return () => request.abort();
  }, [selectedId, from, to, analysisKey]);

  const currentAnalysis = analysisResult?.key === analysisKey ? analysisResult : null;
  const analysis = currentAnalysis?.value;
  const analysisLoading = Boolean(selectedId && !currentAnalysis);
  const analysisError = currentAnalysis?.error;
  const showsAnalysis = page === 'overview' || page === 'compare';
  const latest = analysis?.latest || [];
  const stats = analysis?.stats || calculateStats(latest);
  const filtered = products.filter((p) => `${p.name} ${p.category}`.toLowerCase().includes(search.toLowerCase()));
  const selectedProduct = products.find((p) => String(p.id) === String(selectedId));
  const averageAll = calculateStats(allPrices).average;
  const lastDate = allPrices.length ? [...allPrices].sort((a,b) => b.date.localeCompare(a.date))[0].date : null;
  const editablePrices = admin && page === 'compare'
    ? new Set(getLatestByStore(allPrices.filter((entry) => String(entry.productId) === selectedId)).map(({ id }) => id))
    : null;

  function navigate(target) {
    if (location.hash.startsWith('#product=')) history.replaceState(null, '', location.pathname + location.search);
    setPage(target);
    setMessage('');
  }
  function compare(productId) {
    setSelectedId(String(productId));
    navigate('compare');
  }
  async function toggleFavorite(id, saved) {
    setBusyFavorites((previous) => new Set(previous).add(id));
    try {
      await api.setFavorite(id, saved);
      setFavorites((previous) => {
        const next = new Set(previous);
        if (saved) next.add(id);
        else next.delete(id);
        return next;
      });
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusyFavorites((previous) => {
        const remaining = new Set(previous);
        remaining.delete(id);
        return remaining;
      });
    }
  }
  function retryFavorites() {
    setFavoriteError('');
    setFavoriteAttempt((attempt) => attempt + 1);
  }

  function changed(text) { setMessage(text); setError(''); setTick((old) => old + 1); }
  async function saveProduct(form) {
    if (editing) await api.updateProduct(editing.id, form);
    else await api.createProduct(form);
    setModal(''); setEditing(null); changed(editing ? 'Товар обновлён.' : 'Товар добавлен.');
  }
  async function deleteProduct(p) {
    if (!window.confirm(`Удалить товар «${p.name}» и всю его историю цен?`)) return;
    try { await api.deleteProduct(p.id); changed('Товар удалён вместе с его историей.'); }
    catch (e) { setError(e.message); }
  }
  async function savePrice(form) {
    await api.createPrice(form);
    setSelectedId(String(form.productId));
    setModal(''); changed('Цена сохранена, показатели обновлены.');
  }
  async function savePriceChange(amount) {
    await api.updatePrice(editingPrice.id, amount);
    setModal('');
    changed('Цена сохранена, предыдущая запись осталась в истории.');
    inbox.refresh();
  }
  async function deletePrice(record) {
    if (!window.confirm('Удалить эту запись о цене?')) return;
    try { await api.deletePrice(record.id); changed('Запись удалена.'); }
    catch (e) { setError(e.message); }
  }
  async function importFile(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    setBusyImport(true); setError('');
    try {
      if (!file.name.toLowerCase().endsWith('.xlsx')) throw new Error('Выберите файл Excel (.xlsx).');
      const result = await api.importXlsx(file);
      changed(`Импортировано: ${result.imported}. Дубликатов пропущено: ${result.duplicatesSkipped}.`);
    } catch (e) { setError(e.message); }
    finally { setBusyImport(false); event.target.value = ''; }
  }

  return <div className="app">
    <aside className="sidebar">
      <div className="logo"><span>₽</span><div><b>Price Analyzer</b></div></div>
      <div className="nav-caption">РАБОЧАЯ ОБЛАСТЬ</div>
      <nav aria-label="Навигация">{NAV.filter(([id]) => id !== 'notifications' && (admin || id !== 'settings')).map(([id, icon, label]) =>
        <button key={id} onClick={() => navigate(id)} aria-current={page === id ? 'page' : undefined} className={`nav-link ${page === id ? 'active' : ''}`}>
          <span className="nav-icon" aria-hidden="true">{icon}</span>{label}
        </button>)}</nav>
    </aside>

    <main className="workspace">
      <header className="topbar">
        <div>Рабочая область <span>/</span> <b>{NAV.find(([id]) => id === page)?.[2]}</b></div>
        <div className="account-bar">
          <NotificationBell count={inbox.count} active={page === 'notifications'} onOpen={() => {
            navigate('notifications');
            inbox.refresh();
          }} />
          <span>{session.email}</span><button className="btn secondary" onClick={async () => {
          try { await api.logout(); onLogout(); } catch (failure) { setError(failure.message); }
        }}>Выйти</button></div>
      </header>
      <div className="content">
        <div className="heading">
          <div>
            <h1>{page === 'overview' ? 'Обзор цен' : NAV.find(([id]) => id === page)?.[2]}</h1>
            <p>{page === 'overview' ? 'Мониторинг цен и выгодных предложений в разных магазинах.'
              : page === 'catalog' ? 'Товары и категории для наблюдения за ценами.'
              : page === 'favorites' ? 'Товары, за ценами которых вы следите.'
              : page === 'compare' ? 'Анализ последних цен в магазинах для выбранного товара.'
              : page === 'reports' ? 'Сводные изменения цен по товарам, категориям и магазинам.'
              : page === 'import' && (admin ? 'Загрузка и выгрузка истории цен в Excel (.xlsx).' : 'Выгрузка истории цен в Excel (.xlsx).')}</p></div>
          {admin && ['overview', 'catalog', 'compare'].includes(page) && <button className="btn primary" disabled={!products.length} onClick={() => setModal('price')}>＋ Добавить цену</button>}
        </div>

        {error && <div className="alert error" role="alert"><b>Ошибка:</b> {error}<button aria-label="Закрыть ошибку" onClick={() => setError('')}>×</button></div>}
        {message && <div className="alert success" role="status">{message}<button aria-label="Закрыть уведомление" onClick={() => setMessage('')}>×</button></div>}
        {showsAnalysis && analysisLoading && <p aria-live="polite">Загружаем цены…</p>}
        {showsAnalysis && analysisError && <div className="alert error" role="alert">{analysisError}</div>}

        {page === 'overview' && <>
          <div className="metrics">
            <Metric icon="▤" title="Товаров в каталоге" value={products.length} caption="Количество позиций" />
            <Metric icon="↗" title="Записей о ценах" value={allPrices.length} caption="История наблюдений" />
            <Metric icon="₽" title="Средняя цена записи" value={formatPrice(averageAll)} caption="По всем записям" />
            <Metric icon="◷" title="Последнее обновление" value={lastDate ? dateLabel(lastDate) : '—'} caption="Дата нового наблюдения" />
          </div>
          <section className="panel">
            <div className="panel-head"><div><h2>Динамика цен</h2><p>История изменения стоимости по магазинам</p></div>
              <select aria-label="Товар для графика" value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
            </div>
            <DateRange from={from} to={to} setFrom={setFrom} setTo={setTo} />
            {!analysisLoading && !analysisError && <LineChart history={analysis?.history || []} />}
          </section>
          <section className="panel">
            <div className="panel-head"><div><h2>Цены по магазинам</h2><p>Последние известные предложения для «{selectedProduct?.name || '—'}»</p></div><button className="text-btn" onClick={() => setPage('compare')}>Подробнее →</button></div>
            {!latest.length ? !analysisLoading && !analysisError && <div className="empty">Для выбранного товара и периода нет цен.</div> : <div className="store-list">
              {latest.map((p) => {
                const best = Number(p.price) === Number(stats.min);
                return <div className="store-item" key={p.id}>
                  <div className="store-avatar">{p.store[0]}</div><div className="store-name"><b>{p.store}</b><small>Цена на {dateLabel(p.date)}</small></div>
                  <div className="bar"><i style={{ width: `${Number(p.price) / Number(stats.max) * 100}%`, background: best ? '#12ad91' : '#b7b8ed' }} /></div>
                  <strong>{formatPrice(p.price)}</strong>{best && <span className="best">Выгодно</span>}
                </div>;
              })}
            </div>}
          </section>
        </>}

        {page === 'catalog' && <section className="panel">
          <div className="panel-head"><div><h2>Все товары</h2><p>Сейчас в каталоге: {products.length}</p></div>
            {admin && <button className="btn secondary" onClick={() => { setEditing(null); setModal('product'); }}>＋ Новый товар</button>}</div>
          <div className="search"><span>⌕</span><input aria-label="Поиск товаров" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Поиск по названию и категории..." /></div>
          {favoriteError && <div className="alert error" role="alert">{favoriteError} <button type="button" onClick={retryFavorites}>Повторить загрузку избранного</button></div>}
          <div className="table-scroll"><table><thead><tr><th>ТОВАР</th><th>КАТЕГОРИЯ</th><th>ЕДИНИЦА</th><th>ДЕЙСТВИЯ</th></tr></thead><tbody>
            {filtered.map((p) => <tr key={p.id}><td>
              <div className="catalog-product">
                <FavoriteButton product={p} saved={favorites?.has(p.id) ?? false}
                  disabled={!favorites || busyFavorites.has(p.id)} onToggle={toggleFavorite} />
                <div><b>{p.name}</b>{p.description && <p className="product-description">{p.description}</p>}</div>
              </div></td>
              <td><span className="chip">{p.category}</span></td><td>{p.unit}</td>
              <td className="actions"><button onClick={() => compare(p.id)}>Сравнить</button>
                {admin && <><button onClick={() => { setEditing(p); setModal('product'); }}>Изменить</button>
                  <button className="danger-btn" onClick={() => deleteProduct(p)}>Удалить</button></>}</td></tr>)}
          </tbody></table>{!filtered.length && <div className="empty">Товары не найдены.</div>}</div>
        </section>}

        {page === 'favorites' && <Favorites products={products} favorites={favorites} busy={busyFavorites}
          onToggle={toggleFavorite} onCompare={compare} onCatalog={() => navigate('catalog')} loadError={favoriteError} retry={retryFavorites} />}

        {page === 'notifications' && <Notifications revision={inbox.revision} onRead={inbox.refresh}
          onFavorites={() => navigate('favorites')} />}

        {page === 'compare' && <>
          <section className="panel selector"><div><h2>Выбранный товар</h2><p>Для каждого магазина берётся последняя цена за период</p></div><select aria-label="Товар для сравнения" value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></section>
          <DateRange from={from} to={to} setFrom={setFrom} setTo={setTo} />
          <div className="metrics three">
            <Metric icon="↓" title="Минимальная" value={formatPrice(stats.min)} caption="Лучшая цена" />
            <Metric icon="≈" title="Средняя по магазинам" value={formatPrice(stats.average)} caption="Последние цены" />
            <Metric icon="↑" title="Максимальная" value={formatPrice(stats.max)} caption="Дорогое предложение" />
          </div>
          <section className="panel"><div className="panel-head"><div><h2>Сравнение магазинов</h2><p>Цены упорядочены по возрастанию</p></div></div>
            <div className="table-scroll"><table><thead><tr><th>МАГАЗИН</th><th>ЦЕНА</th><th>ДАТА</th><th>РАЗНИЦА</th>{admin && <th>ДЕЙСТВИЯ</th>}</tr></thead><tbody>
              {latest.map((p) => {
                const difference = Number(p.price) - Number(stats.min);
                const best = difference === 0;
                return <tr key={p.id}><td><b>{p.store}</b>{best && <span className="best inline">Минимум</span>}</td><td className={best ? 'green-text' : ''}><b>{formatPrice(p.price)}</b></td><td>{p.date}</td><td>{best ? '—' : `+${formatPrice(difference)}`}</td>
                  {admin && <td className="actions">{editablePrices.has(p.id) && <button onClick={() => {
                    setEditingPrice(p);
                    setModal('editPrice');
                  }}>Изменить цену</button>}</td>}</tr>;
              })}
            </tbody></table>{!latest.length && !analysisLoading && !analysisError && <div className="empty">Для выбранного товара нет цен.</div>}</div>
          </section>
          <section className="panel"><div className="panel-head"><div><h2>История записей</h2></div></div>
            <div className="table-scroll"><table><thead><tr><th>МАГАЗИН</th><th>ЦЕНА</th><th>ДАТА</th><th></th></tr></thead><tbody>
              {(analysis?.history || []).map((p) => <tr key={p.id}><td>{p.store}</td><td>{formatPrice(p.price)}</td><td>{p.date}</td><td className="actions">{admin && <button className="danger-btn" onClick={() => deletePrice(p)}>Удалить</button>}</td></tr>)}
            </tbody></table></div>
          </section>
        </>}

        {page === 'reports' && <Reports products={products} prices={allPrices} />}
        {page === 'settings' && admin && <AdminSettings />}

        {page === 'import' && <div className="import-grid">
          {admin && <section className="panel import-card">
            <div className="import-icon">⇪</div><h2>Импорт истории цен</h2>
            <p>Загрузите заполненный шаблон или ранее выгруженную историю цен.</p>
            <code>productId | store | price | date</code>
            <button type="button" className="btn secondary"
              onClick={() => api.downloadImportTemplate().catch((failure) => setError(failure.message))}>Скачать шаблон XLSX</button>
            <label className="file-button">{busyImport ? 'Загрузка…' : 'Выбрать XLSX'}
              <input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                disabled={busyImport} onChange={importFile} />
            </label>
            <small>Дата Excel или YYYY-MM-DD. Повторные записи пропускаются.</small>
          </section>}
          <section className="panel import-card">
            <div className="import-icon">⇩</div><h2>Экспорт истории цен</h2>
            <p>Наблюдения по ценам с названиями товаров, магазинами и датами.</p>
            <select aria-label="Товар для выгрузки" value={exportFilter.productId}
              onChange={(event) => setExportFilter((previous) => ({ ...previous, productId: event.target.value }))}>
              <option value="">Все товары</option>
              {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
            </select>
            <DateRange from={exportFilter.from} to={exportFilter.to}
              setFrom={(from) => setExportFilter((previous) => ({ ...previous, from }))}
              setTo={(to) => setExportFilter((previous) => ({ ...previous, to }))} />
            <button type="button" className="btn primary download"
              onClick={() => api.downloadHistory(exportFilter).catch((failure) => setError(failure.message))}>Скачать историю XLSX</button>
          </section>
        </div>}
      </div>
    </main>

    {modal && <Modal label={modal === 'product' ? 'Форма товара' : 'Форма цены'} onClose={() => setModal('')}>
      <div className="dialog-head"><h2>{modal === 'product' ? editing ? 'Изменить товар' : 'Новый товар' : modal === 'editPrice' ? 'Изменить цену' : 'Добавить цену'}</h2><button className="close" onClick={() => setModal('')} aria-label="Закрыть">×</button></div>
      {modal === 'product'
        ? <ProductForm initial={editing} onSave={saveProduct} onCancel={() => setModal('')} />
        : modal === 'editPrice' ? <PriceUpdateForm record={editingPrice} onSave={savePriceChange} onCancel={() => setModal('')} />
        : <PriceForm products={products} selectedId={selectedId} onSave={savePrice} onCancel={() => setModal('')} />}
    </Modal>}
  </div>;
}

export default function App() {
  const [session, setSession] = useState(undefined);
  const [problem, setProblem] = useState('');
  const [resetToken, setResetToken] = useState(() => new URLSearchParams(location.hash.slice(1)).get('reset'));
  useEffect(() => {
    function consumeResetLink() {
      const token = new URLSearchParams(location.hash.slice(1)).get('reset');
      if (!token) return;
      setResetToken(token);
      history.replaceState(null, '', location.pathname + location.search);
    }
    consumeResetLink();
    window.addEventListener('hashchange', consumeResetLink);
    return () => window.removeEventListener('hashchange', consumeResetLink);
  }, []);

  useEffect(() => {
    let active = true;
    const expired = () => setSession(null);
    const updated = (event) => setSession(event.detail);
    window.addEventListener('price-analyzer:session-expired', expired);
    window.addEventListener('price-analyzer:session-changed', updated);
    api.restoreSession().then(
      (value) => { if (active) setSession(value); },
      (failure) => {
        if (!active) return;
        if (failure.status !== 401) setProblem(failure.message);
        setSession(null);
      },
    );
    return () => {
      active = false;
      window.removeEventListener('price-analyzer:session-expired', expired);
      window.removeEventListener('price-analyzer:session-changed', updated);
    };
  }, []);

  if (session === undefined && !resetToken) {
    return <main className="account-shell"><p role="status">Проверяем сеанс…</p></main>;
  }
  if (!session || resetToken) {
    return <AccountForm resetToken={resetToken} onReset={() => { setResetToken(null); setSession(null); }}
      initialError={problem} onSession={setSession} />;
  }
  return <Workspace key={`${session.email}:${session.role}`} session={session} onLogout={() => setSession(null)} />;
}

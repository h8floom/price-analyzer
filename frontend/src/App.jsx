import { useEffect, useMemo, useRef, useState } from 'react';
import { api } from './lib/api.js';
import AccountForm from './AccountForm.jsx';
import Reports from './Reports.jsx';
import AdminSettings from './AdminSettings.jsx';
import Favorites, { FavoriteButton } from './Favorites.jsx';
import Notifications, { NotificationBell, useUnreadNotifications } from './Notifications.jsx';
import PriceChart from './PriceChart.jsx';
import InterfaceIcon, { Brand } from './InterfaceIcon.jsx';
import { calculateStats, dateLabel, formatPrice, getLatestByStore } from './lib/priceUtils.js';

const NAV = [
  ['overview', 'Обзор'],
  ['catalog', 'Каталог товаров'],
  ['favorites', 'Избранное'],
  ['compare', 'Сравнение цен'],
  ['import', 'Импорт / экспорт'],
  ['reports', 'Отчёты'],
  ['settings', 'Управление'],
];
const PAGE_TITLES = Object.fromEntries([...NAV, ['overview', 'Обзор цен'], ['notifications', 'Уведомления']]);
const PAGE_NOTES = {
  overview: 'История наблюдений и последние предложения магазинов',
  catalog: 'Поиск по товарам и категориям, цены по последним наблюдениям',
  favorites: 'Товары, за ценами которых вы следите',
  compare: 'Последняя цена каждого магазина в выбранном периоде',
  reports: 'Изменения цен по товарам, категориям и магазинам',
};

function DateRange({ from, to, setFrom, setTo }) {
  const start = useRef(null);
  return <div className="date-filter">
    <label>Начало периода<input ref={start} type="date" name="from" value={from} onChange={(event) => setFrom(event.target.value)} /></label>
    <label>Конец периода<input type="date" name="to" value={to} onChange={(event) => setTo(event.target.value)} /></label>
    {(from || to) && <button type="button" onClick={() => { setFrom(''); setTo(''); start.current?.focus(); }}
      className="text-btn">Сбросить период</button>}
  </div>;
}

function Metric({ title, value }) {
  return <div className="metric"><dt>{title}</dt><dd><strong>{value}</strong></dd></div>;
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
    <label>Название<input name="name" autoComplete="off" required maxLength="240" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} placeholder="Например, Кофе молотый, 250 г…" /></label>
    <label>Категория<input name="category" autoComplete="off" required maxLength="120" value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })} /></label>
    <label>Единица<input name="unit" autoComplete="off" required maxLength="64" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} /></label>
    <label>Описание<textarea name="description" maxLength={2000} rows={3} value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
    {error && <div role="alert" className="field-error">{error}</div>}
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button><button className="btn primary" disabled={busy} type="submit">{busy ? 'Сохранение…' : 'Сохранить товар'}</button></div>
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
    <label>Товар<select name="productId" required value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></label>
    <label>Магазин<input name="store" autoComplete="off" required maxLength="160" value={form.store} onChange={(e) => setForm({ ...form, store: e.target.value })} /></label>
    <div className="two-col">
      <label>Цена, ₽<input name="price" required type="number" inputMode="decimal" min="0.01" step="0.01" value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} /></label>
      <label>Дата<input name="date" required type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></label>
    </div>
    {error && <div role="alert" className="field-error">{error}</div>}
    <p className="helper">Если цена за эту дату уже есть, измените последнее наблюдение в сравнении цен.</p>
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button><button disabled={busy} className="btn primary" type="submit">{busy ? 'Сохранение…' : 'Сохранить цену'}</button></div>
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
  const [menuOpen, setMenuOpen] = useState(false);
  const [products, setProducts] = useState([]);
  const [catalogLoaded, setCatalogLoaded] = useState(false);
  const [selectedId, setSelectedId] = useState('');
  const [analysisResult, setAnalysisResult] = useState(null);
  const [allPrices, setAllPrices] = useState([]);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [modal, setModal] = useState('');
  const [editing, setEditing] = useState(null);
  const [editingPrice, setEditingPrice] = useState(null);
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [tick, setTick] = useState(0);
  const [busyImport, setBusyImport] = useState(false);
  const [exportFilter, setExportFilter] = useState({ productId: '', from: '', to: '' });
  const [favorites, setFavorites] = useState(null);
  const [busyFavorites, setBusyFavorites] = useState(() => new Set());
  const [favoriteError, setFavoriteError] = useState('');
  const [favoriteAttempt, setFavoriteAttempt] = useState(0);
  const headingRef = useRef(null);
  const pendingHeadingFocus = useRef(false);
  const menuButton = useRef(null);
  const navigation = useRef(null);
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
        setCatalogLoaded(true);
        setSelectedId((old) => list.some((p) => String(p.id) === String(old))
          ? old : String(list[0]?.id || ''));
        setError('');
      })
      .catch((e) => {
        if (!ignore) {
          setCatalogLoaded(true);
          setError(e.message);
        }
      });
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

  useEffect(() => {
    if (!pendingHeadingFocus.current) return;
    pendingHeadingFocus.current = false;
    headingRef.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, [page]);

  useEffect(() => {
    if (menuOpen) {
      const active = navigation.current?.querySelector('[aria-current="page"]') || navigation.current?.querySelector('button');
      active?.focus();
    }
  }, [menuOpen]);

  const currentAnalysis = analysisResult?.key === analysisKey ? analysisResult : null;
  const analysis = currentAnalysis?.value;
  const analysisLoading = Boolean(selectedId && !currentAnalysis);
  const analysisError = currentAnalysis?.error;
  const showsAnalysis = page === 'overview' || page === 'compare';
  const latest = analysis?.latest || [];
  const stats = analysis?.stats || calculateStats(latest);
  const categories = useMemo(() => [...new Set(products.map((product) => product.category))].sort(), [products]);
  const pricesByProduct = useMemo(() => {
    const groups = new Map();
    for (const price of allPrices) {
      if (!groups.has(price.productId)) groups.set(price.productId, []);
      groups.get(price.productId).push(price);
    }
    return new Map([...groups].map(([id, entries]) => [id, getLatestByStore(entries)[0].price]));
  }, [allPrices]);
  const query = search.trim().toLowerCase();
  const filtered = products.filter((product) => (!category || product.category === category)
    && `${product.name} ${product.category}`.toLowerCase().includes(query));
  const averageAll = calculateStats(allPrices).average;
  const lastDate = allPrices.length ? [...allPrices].sort((a,b) => b.date.localeCompare(a.date))[0].date : null;
  const editablePrices = admin && page === 'compare'
    ? new Set(getLatestByStore(allPrices.filter((entry) => String(entry.productId) === selectedId)).map(({ id }) => id))
    : null;

  function navigate(target) {
    if (location.hash.startsWith('#product=')) history.replaceState(null, '', location.pathname + location.search);
    setMenuOpen(false);
    if (target === page) {
      headingRef.current?.focus({ preventScroll: true });
      window.scrollTo(0, 0);
    } else {
      pendingHeadingFocus.current = true;
    }
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
    <a className="skip-link" href="#workspace">К содержимому</a>
    <header className="topbar">
      <div className="topbar-start">
        <button type="button" className="personal-icon mobile-menu" ref={menuButton} aria-expanded={menuOpen}
          aria-controls="workspace-navigation" aria-label={menuOpen ? 'Закрыть меню' : 'Открыть меню'}
          onClick={() => setMenuOpen((previous) => !previous)}>
          <InterfaceIcon name={menuOpen ? 'close' : 'menu'} />
        </button>
        <span className="mobile-brand" translate="no">Price Analyzer</span>
      </div>
      <div className="account-bar">
        <NotificationBell count={inbox.count} active={page === 'notifications'} onOpen={() => {
          navigate('notifications');
          inbox.refresh();
        }} />
        <div className="account-identity">
          <span className="account-email" title={session.email} translate="no">{session.email}</span>
          <small>{admin ? 'Администратор' : 'Пользователь'}</small>
        </div>
        <button type="button" className="btn secondary" onClick={async () => {
          try { await api.logout(); onLogout(); } catch (failure) { setError(failure.message); }
        }}>Выйти</button>
      </div>
    </header>
    <aside className={`sidebar${menuOpen ? ' open' : ''}`}>
      <Brand />
      <nav id="workspace-navigation" ref={navigation} aria-label="Навигация" onKeyDown={(event) => {
        if (event.key === 'Escape' && menuOpen) {
          setMenuOpen(false);
          menuButton.current?.focus();
        }
      }}>{NAV.filter(([id]) => admin || id !== 'settings').map(([id, label]) =>
        <button type="button" key={id} onClick={() => navigate(id)} aria-current={page === id ? 'page' : undefined}
          className="nav-link">
          <InterfaceIcon name={id} />{label}
        </button>)}</nav>
    </aside>

    <main id="workspace" className="workspace" tabIndex={-1}>
      <div className="content">
        <div className="heading">
          <div>
            <h1 ref={headingRef} tabIndex={-1}>{PAGE_TITLES[page]}</h1>
            {PAGE_NOTES[page] && <p>{PAGE_NOTES[page]}</p>}
          </div>
          {admin && ['overview', 'catalog', 'compare'].includes(page) && <button type="button" className="btn primary"
            disabled={!products.length} onClick={() => setModal('price')}><InterfaceIcon name="plus" />Добавить цену</button>}
        </div>

        {error && <div className="alert error" role="alert"><span>{error}</span><button type="button" className="icon-dismiss" aria-label="Закрыть ошибку" onClick={() => setError('')}><InterfaceIcon name="close" /></button></div>}
        {message && <div className="alert success" role="status"><span>{message}</span><button type="button" className="icon-dismiss" aria-label="Закрыть уведомление" onClick={() => setMessage('')}><InterfaceIcon name="close" /></button></div>}
        {showsAnalysis && analysisLoading && <p aria-live="polite">Загружаем цены…</p>}
        {showsAnalysis && analysisError && <div className="alert error" role="alert">{analysisError}</div>}

        {page === 'overview' && <>
          <dl className="metrics">
            <Metric title="Товаров" value={catalogLoaded ? products.length : '—'} />
            <Metric title="Наблюдений" value={catalogLoaded ? allPrices.length : '—'} />
            <Metric title="Средняя цена записи" value={formatPrice(averageAll)} />
            <Metric title="Последнее наблюдение" value={lastDate ? dateLabel(lastDate) : '—'} />
          </dl>
          <section className="panel overview-analysis">
            <div className="analysis-controls">
              <div className="panel-head"><h2>Динамика цен</h2>
                <label className="inline-field">Товар<select aria-label="Товар для графика" name="product" value={selectedId}
                  disabled={!products.length} onChange={(event) => setSelectedId(event.target.value)}>
                  {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
                </select></label>
              </div>
              <DateRange from={from} to={to} setFrom={setFrom} setTo={setTo} />
            </div>
            <div className="analysis-body">
              <div className="trend">
                {!analysisLoading && !analysisError && <PriceChart history={analysis?.history} />}
              </div>
              <section className="offers-preview">
                <div className="panel-head"><div><h3>Цены по магазинам</h3><p>Последние за период</p></div></div>
                {latest.length ? <ul className="store-list">
                  {latest.map((price) => {
                    const best = Number(price.price) === Number(stats.min);
                    return <li className="store-item" key={price.id}>
                      <div className="store-name"><b>{price.store}</b><small>{dateLabel(price.date)}</small></div>
                      <div className="offer-value"><strong>{formatPrice(price.price)}</strong>
                        {best && <span className="best">Минимум</span>}</div>
                    </li>;
                  })}
                </ul> : !analysisLoading && !analysisError && <p className="empty">Предложений за этот период нет</p>}
                {selectedId && <button type="button" className="btn secondary" onClick={() => navigate('compare')}>Открыть сравнение</button>}
              </section>
            </div>
          </section>
        </>}

        {page === 'catalog' && <section className="panel catalog-panel" aria-busy={!catalogLoaded}>
          <div className="panel-head">
            <div><h2>Все товары</h2>{catalogLoaded && <p>Найдено {filtered.length} из {products.length}</p>}</div>
            {admin && <button type="button" className="btn secondary" onClick={() => { setEditing(null); setModal('product'); }}>
              <InterfaceIcon name="plus" />Новый товар
            </button>}
          </div>
          <div className="catalog-filters">
            <label className="search"><span>Поиск товаров</span><span className="search-field">
              <InterfaceIcon name="search" />
              <input name="query" type="search" autoComplete="off" placeholder="Например, кофе…"
                value={search} onChange={(event) => setSearch(event.target.value)} />
            </span></label>
            <label>Категория<select name="category" value={category} onChange={(event) => setCategory(event.target.value)}>
              <option value="">Все категории</option>
              {categories.map((option) => <option key={option}>{option}</option>)}
            </select></label>
          </div>
          {favoriteError && <div className="alert error" role="alert"><span>{favoriteError}</span>
            <button type="button" className="text-btn" onClick={retryFavorites}>Повторить загрузку избранного</button>
          </div>}
          {!catalogLoaded ? <p className="empty" role="status">Загрузка каталога…</p> : <>
            <div className="table-scroll" role="region" aria-label="Список товаров" tabIndex={0}>
              <table className="catalog-table"><thead><tr>
                <th scope="col">Товар</th><th scope="col" className="catalog-desktop">Категория</th>
                <th scope="col" className="catalog-desktop">Единица</th><th scope="col" className="numeric">Цена от</th>
                <th scope="col">Действия</th>
              </tr></thead><tbody>
                {filtered.map((product) => <tr key={product.id}><td>
                  <div className="catalog-product">
                    <FavoriteButton product={product} saved={favorites?.has(product.id) ?? false}
                      disabled={!favorites || busyFavorites.has(product.id)} onToggle={toggleFavorite} />
                    <div>
                      <b>{product.name}</b>
                      <small className="catalog-mobile">{product.category} / {product.unit}</small>
                      {product.description && <p className="product-description">{product.description}</p>}
                    </div>
                  </div>
                </td><td className="catalog-desktop">{product.category}</td><td className="catalog-desktop">{product.unit}</td>
                  <td className="numeric">{formatPrice(pricesByProduct.get(product.id))}</td>
                  <td><div className="actions"><button type="button" onClick={() => compare(product.id)}>Сравнить</button>
                    {admin && <><button type="button" onClick={() => { setEditing(product); setModal('product'); }}>Изменить</button>
                      <button type="button" className="danger-btn" onClick={() => deleteProduct(product)}>Удалить</button></>}
                  </div></td>
                </tr>)}
              </tbody></table>
            </div>
            {!filtered.length && <div className="empty">
              <p>{products.length ? 'Товаров по этим условиям нет' : 'Каталог пока пуст'}</p>
              {products.length > 0 && <button type="button" className="text-btn" onClick={() => { setSearch(''); setCategory(''); }}>
                Сбросить фильтры
              </button>}
            </div>}
          </>}
        </section>}

        {page === 'favorites' && <Favorites products={products} favorites={favorites} busy={busyFavorites}
          onToggle={toggleFavorite} onCompare={compare} onCatalog={() => navigate('catalog')} loadError={favoriteError} retry={retryFavorites} />}

        {page === 'notifications' && <Notifications revision={inbox.revision} onRead={inbox.refresh}
          onFavorites={() => navigate('favorites')} />}

        {page === 'compare' && <>
          <section className="panel selector">
            <label>Товар для сравнения<select aria-label="Товар для сравнения" name="product" value={selectedId} disabled={!products.length}
              onChange={(event) => setSelectedId(event.target.value)}>
              {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
            </select></label>
            <DateRange from={from} to={to} setFrom={setFrom} setTo={setTo} />
          </section>
          <dl className="metrics three">
            <Metric title="Минимальная" value={formatPrice(stats.min)} />
            <Metric title="Средняя по магазинам" value={formatPrice(stats.average)} />
            <Metric title="Максимальная" value={formatPrice(stats.max)} />
          </dl>
          <section className="panel comparison-table"><div className="panel-head"><div><h2>Сравнение магазинов</h2><p>От меньшей цены к большей</p></div></div>
            {latest.length > 0 && <p className="table-hint">Прокрутите таблицу вправо для остальных столбцов</p>}
            <div className="table-scroll" role="region" aria-label="Предложения магазинов" tabIndex={0}>
              <table><thead><tr><th scope="col">Магазин</th><th scope="col" className="numeric">Цена</th><th scope="col">Дата</th>
                <th scope="col" className="numeric">Разница</th>{admin && <th scope="col">Действия</th>}</tr></thead><tbody>
              {latest.map((p) => {
                const difference = Number(p.price) - Number(stats.min);
                const best = difference === 0;
                return <tr key={p.id}><td><b>{p.store}</b>{best && <span className="best inline">Минимум</span>}</td>
                  <td className="numeric"><b>{formatPrice(p.price)}</b></td><td><time dateTime={p.date}>{p.date}</time></td>
                  <td className="numeric">{best ? '—' : `+${formatPrice(difference)}`}</td>
                  {admin && <td><div className="actions">{editablePrices.has(p.id) && <button type="button" onClick={() => {
                    setEditingPrice(p);
                    setModal('editPrice');
                  }}>Изменить цену</button>}</div></td>}</tr>;
              })}
            </tbody></table>{!latest.length && !analysisLoading && !analysisError && <div className="empty">Для выбранного товара нет цен.</div>}</div>
          </section>
          {!analysisLoading && !analysisError && analysis?.history.length > 0 && <section className="panel compare-chart">
            <h2>Динамика цен</h2><PriceChart history={analysis.history} />
          </section>}
          <section className="panel history-table"><div className="panel-head"><h2>История записей</h2></div>
            <div className="table-scroll" role="region" aria-label="История наблюдений" tabIndex={0}>
              <table><thead><tr><th scope="col">Магазин</th><th scope="col" className="numeric">Цена</th>
                <th scope="col">Дата</th>{admin && <th scope="col">Действия</th>}</tr></thead><tbody>
              {(analysis?.history || []).map((p) => <tr key={p.id}>
                <td>{p.store}</td><td className="numeric">{formatPrice(p.price)}</td>
                <td><time dateTime={p.date}>{p.date}</time></td>{admin && <td><div className="actions">
                  <button className="danger-btn" type="button" onClick={() => deletePrice(p)}>Удалить</button>
                </div></td>}
              </tr>)}
            </tbody></table></div>
          </section>
        </>}

        {page === 'reports' && <Reports products={products} prices={allPrices} />}
        {page === 'settings' && admin && <AdminSettings />}

        {page === 'import' && <div className="import-grid">
          {admin && <section className="panel import-card">
            <h2>Импорт истории цен</h2>
            <p>Подойдёт заполненный шаблон или выгрузка истории</p>
            <code translate="no">productId | store | price | date</code>
            <label className="file-button">{busyImport ? 'Импорт…' : 'Выбрать XLSX'}
              <input name="pricesFile" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                disabled={busyImport} onChange={importFile} />
            </label>
            <button type="button" className="text-btn"
              onClick={() => api.downloadImportTemplate().catch((failure) => setError(failure.message))}>Скачать шаблон XLSX</button>
            <small>Дата Excel или YYYY-MM-DD. Повторные записи пропускаются.</small>
          </section>}
          <section className="panel import-card">
            <h2>Экспорт истории цен</h2>
            <p>Товары, магазины, цены и даты наблюдений</p>
            <label>Товар<select aria-label="Товар для выгрузки" name="product" value={exportFilter.productId}
              onChange={(event) => setExportFilter((previous) => ({ ...previous, productId: event.target.value }))}>
              <option value="">Все товары</option>
              {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
            </select></label>
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
      <div className="dialog-head"><h2>{modal === 'product' ? editing ? 'Изменить товар' : 'Новый товар' : modal === 'editPrice' ? 'Изменить цену' : 'Добавить цену'}</h2>
        <button type="button" className="personal-icon" onClick={() => setModal('')} aria-label="Закрыть"><InterfaceIcon name="close" /></button>
      </div>
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

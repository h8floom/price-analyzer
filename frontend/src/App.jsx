import { useEffect, useMemo, useState } from 'react';
import { api } from './lib/api.js';
import AccountForm from './AccountForm.jsx';
import { calculateStats, dateLabel, formatPrice, getSeries } from './lib/priceUtils.js';

const NAV = [
  ['overview', '▦', 'Обзор'],
  ['catalog', '▤', 'Каталог товаров'],
  ['compare', '↗', 'Сравнение цен'],
  ['import', '⇪', 'Импорт / экспорт'],
];

function LineChart({ history }) {
  const { dates, stores } = useMemo(() => getSeries(history), [history]);
  if (!history.length) return <div className="empty">Для выбранного периода пока нет цен.</div>;
  const colors = ['#7367f0', '#13ae99', '#f0a34e', '#d86cad', '#4b92d4'];
  const values = history.map((p) => Number(p.price));
  const min = Math.floor(Math.min(...values) / 20) * 20;
  const max = Math.ceil(Math.max(...values, min + 20) / 20) * 20;
  const coords = (date, price) => [
    60 + (dates.length === 1 ? 310 : dates.indexOf(date) * 620 / (dates.length - 1)),
    240 - (Number(price) - min) * 190 / (max - min),
  ];
  const ticks = [0, 1, 2, 3, 4].map((x) => min + x * (max - min) / 4);
  return <div className="chart-wrap">
    <svg viewBox="0 0 720 280" role="img" aria-label="Линейный график динамики цен">
      {ticks.map((v) => <g key={v}>
        <line x1="60" x2="680" y1={coords(dates[0], v)[1]} y2={coords(dates[0], v)[1]} stroke="#e8ecf5" strokeDasharray="4 6" />
        <text x="48" y={coords(dates[0], v)[1] + 4} textAnchor="end" className="axis">{Math.round(v)} ₽</text>
      </g>)}
      {dates.map((d) => <text key={d} x={coords(d, min)[0]} y="264" textAnchor="middle" className="axis">{dateLabel(d)}</text>)}
      {stores.map((store, index) => {
        const points = history.filter((p) => p.store === store).sort((a, b) => a.date.localeCompare(b.date));
        const line = points.map((p) => coords(p.date, p.price).join(',')).join(' ');
        return <g key={store}>
          {points.length > 1 && <polyline points={line} stroke={colors[index % colors.length]} fill="none" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />}
          {points.map((p) => {
            const [x, y] = coords(p.date, p.price);
            return <circle key={p.id} cx={x} cy={y} r="5" fill={colors[index % colors.length]} stroke="white" strokeWidth="2">
              <title>{`${store}: ${formatPrice(p.price)} — ${p.date}`}</title>
            </circle>;
          })}
        </g>;
      })}
    </svg>
    <div className="legend">{stores.map((s, i) => <span key={s}><i style={{ background: colors[i % colors.length] }} />{s}</span>)}</div>
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
    <p className="helper">Для одного товара, магазина и дня допускается одна запись.</p>
    <div className="form-actions"><button type="button" className="btn secondary" onClick={onCancel}>Отмена</button><button disabled={busy} className="btn primary" type="submit">{busy ? 'Сохранение...' : 'Сохранить цену'}</button></div>
  </form>;
}

function Workspace({ session, onLogout }) {
  const [page, setPage] = useState('overview');
  const [products, setProducts] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [analysis, setAnalysis] = useState(null);
  const [allPrices, setAllPrices] = useState([]);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [modal, setModal] = useState('');
  const [editing, setEditing] = useState(null);
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [tick, setTick] = useState(0);
  const [busyImport, setBusyImport] = useState(false);

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
    if (!selectedId) { setAnalysis(null); return; }
    let ignore = false;
    api.analysis(selectedId, from, to)
      .then((value) => { if (!ignore) setAnalysis(value); })
      .catch((e) => { if (!ignore) { setAnalysis(null); setError(e.message); } });
    return () => { ignore = true; };
  }, [selectedId, from, to, tick]);

  const latest = analysis?.latest || [];
  const stats = analysis?.stats || calculateStats(latest);
  const filtered = products.filter((p) => `${p.name} ${p.category}`.toLowerCase().includes(search.toLowerCase()));
  const selectedProduct = products.find((p) => String(p.id) === String(selectedId));
  const averageAll = calculateStats(allPrices).average;
  const lastDate = allPrices.length ? [...allPrices].sort((a,b) => b.date.localeCompare(a.date))[0].date : null;

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
      const result = await api.importCsv(await file.text());
      changed(`Импортировано: ${result.imported}. Дубликатов пропущено: ${result.duplicatesSkipped}.`);
    } catch (e) { setError(e.message); }
    finally { setBusyImport(false); event.target.value = ''; }
  }

  return <div className="app">
    <aside className="sidebar">
      <div className="logo"><span>₽</span><div><b>Price Analyzer</b><small>АНАЛИЗ ЦЕН</small></div></div>
      <div className="nav-caption">РАБОЧАЯ ОБЛАСТЬ</div>
      <nav aria-label="Навигация">{NAV.map(([id, icon, label]) =>
        <button key={id} onClick={() => { setPage(id); setMessage(''); }} className={`nav-link ${page === id ? 'active' : ''}`}>
          <span className="nav-icon">{icon}</span>{label}
        </button>)}</nav>
    </aside>

    <main className="workspace">
      <header className="topbar">
        <div>Рабочая область <span>/</span> <b>{NAV.find(([id]) => id === page)?.[2]}</b></div>
        <div className="account-bar"><span>{session.email}</span><button className="btn secondary" onClick={async () => {
          try { await api.logout(); onLogout(); } catch (failure) { setError(failure.message); }
        }}>Выйти</button></div>
      </header>
      <div className="content">
        <div className="heading">
          <div><div className="eyebrow">PRICE ANALYTICS</div>
            <h1>{page === 'overview' ? 'Обзор цен' : page === 'catalog' ? 'Каталог товаров' : page === 'compare' ? 'Сравнение цен' : 'Импорт и экспорт'}</h1>
            <p>{page === 'overview' ? 'Мониторинг цен и выгодных предложений в разных магазинах.'
              : page === 'catalog' ? 'Товары и категории для наблюдения за ценами.'
              : page === 'compare' ? 'Анализ последних цен в магазинах для выбранного товара.'
              : 'Загрузка и выгрузка истории цен в формате CSV.'}</p></div>
          {page !== 'import' && <button className="btn primary" disabled={!products.length} onClick={() => setModal('price')}>＋ Добавить цену</button>}
        </div>

        {error && <div className="alert error" role="alert"><b>Ошибка:</b> {error}<button onClick={() => setError('')}>×</button></div>}
        {message && <div className="alert success" role="status">{message}<button onClick={() => setMessage('')}>×</button></div>}

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
            <div className="date-filter"><span>Период:</span><label>с <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label><label>по <input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label><button onClick={() => { setFrom(''); setTo(''); }} className="text-btn">Сбросить</button></div>
            <LineChart history={analysis?.history || []} />
          </section>
          <section className="panel">
            <div className="panel-head"><div><h2>Цены по магазинам</h2><p>Последние известные предложения для «{selectedProduct?.name || '—'}»</p></div><button className="text-btn" onClick={() => setPage('compare')}>Подробнее →</button></div>
            {!latest.length ? <div className="empty">Добавь первую цену для сравнения.</div> : <div className="store-list">
              {latest.map((p, i) => <div className="store-item" key={p.id}>
                <div className="store-avatar">{p.store[0]}</div><div className="store-name"><b>{p.store}</b><small>Цена на {dateLabel(p.date)}</small></div>
                <div className="bar"><i style={{ width: `${Number(p.price) / Math.max(...latest.map((x) => Number(x.price))) * 100}%`, background: i === 0 ? '#12ad91' : '#b7b8ed' }} /></div>
                <strong>{formatPrice(p.price)}</strong>{i === 0 && <span className="best">Выгодно</span>}
              </div>)}
            </div>}
          </section>
        </>}

        {page === 'catalog' && <section className="panel">
          <div className="panel-head"><div><h2>Все товары</h2><p>Сейчас в каталоге: {products.length}</p></div>
            <button className="btn secondary" onClick={() => { setEditing(null); setModal('product'); }}>＋ Новый товар</button></div>
          <div className="search"><span>⌕</span><input aria-label="Поиск товаров" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Поиск по названию и категории..." /></div>
          <div className="table-scroll"><table><thead><tr><th>ТОВАР</th><th>КАТЕГОРИЯ</th><th>ЕДИНИЦА</th><th>ДЕЙСТВИЯ</th></tr></thead><tbody>
            {filtered.map((p) => <tr key={p.id}><td><b>{p.name}</b>{p.description && <p className="product-description">{p.description}</p>}</td><td><span className="chip">{p.category}</span></td><td>{p.unit}</td><td className="actions"><button onClick={() => { setSelectedId(String(p.id)); setPage('compare'); }}>Сравнить</button><button onClick={() => { setEditing(p); setModal('product'); }}>Изменить</button><button className="danger-btn" onClick={() => deleteProduct(p)}>Удалить</button></td></tr>)}
          </tbody></table>{!filtered.length && <div className="empty">Товары не найдены.</div>}</div>
        </section>}

        {page === 'compare' && <>
          <section className="panel selector"><div><h2>Выбранный товар</h2><p>Для каждого магазина берётся последняя цена за период</p></div><select value={selectedId} onChange={(e) => setSelectedId(e.target.value)}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></section>
          <div className="metrics three">
            <Metric icon="↓" title="Минимальная" value={formatPrice(stats.min)} caption="Лучшая цена" />
            <Metric icon="≈" title="Средняя по магазинам" value={formatPrice(stats.average)} caption="Последние цены" />
            <Metric icon="↑" title="Максимальная" value={formatPrice(stats.max)} caption="Дорогое предложение" />
          </div>
          <section className="panel"><div className="panel-head"><div><h2>Сравнение магазинов</h2><p>Цены упорядочены по возрастанию</p></div></div>
            <div className="table-scroll"><table><thead><tr><th>МАГАЗИН</th><th>ЦЕНА</th><th>ДАТА</th><th>РАЗНИЦА</th></tr></thead><tbody>
              {latest.map((p, i) => <tr key={p.id}><td><b>{p.store}</b>{i === 0 && <span className="best inline">Минимум</span>}</td><td className={i === 0 ? 'green-text' : ''}><b>{formatPrice(p.price)}</b></td><td>{p.date}</td><td>{i === 0 ? '—' : `+${formatPrice(Number(p.price) - Number(stats.min))}`}</td></tr>)}
            </tbody></table>{!latest.length && <div className="empty">Для выбранного товара нет цен.</div>}</div>
          </section>
          <section className="panel"><div className="panel-head"><div><h2>История записей</h2><p>Удаление ошибочного измерения</p></div></div>
            <div className="table-scroll"><table><thead><tr><th>МАГАЗИН</th><th>ЦЕНА</th><th>ДАТА</th><th></th></tr></thead><tbody>
              {(analysis?.history || []).map((p) => <tr key={p.id}><td>{p.store}</td><td>{formatPrice(p.price)}</td><td>{p.date}</td><td className="actions"><button className="danger-btn" onClick={() => deletePrice(p)}>Удалить</button></td></tr>)}
            </tbody></table></div>
          </section>
        </>}

        {page === 'import' && <div className="import-grid">
          <section className="panel import-card"><div className="import-icon">⇪</div><h2>Импорт CSV</h2><p>Загрузи новые записи о ценах. Первая строка файла должна содержать заголовки:</p><code>productId,store,price,date</code><label className="file-button"> {busyImport ? 'Загрузка...' : 'Выбрать CSV-файл'}<input type="file" accept=".csv,text/csv" disabled={busyImport} onChange={importFile} /></label><small>Дата: YYYY-MM-DD. Повторные записи пропускаются.</small></section>
          <section className="panel import-card"><div className="import-icon">⇩</div><h2>Экспорт CSV</h2><p>Сохрани историю цен на компьютер. Выбери все товары или только один.</p><select value={selectedId} onChange={(e) => setSelectedId(e.target.value)}><option value="">Все товары</option>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select><a className="btn secondary download" href={`/api/export/prices.csv${selectedId ? `?productId=${selectedId}` : ''}`}>Скачать CSV</a><small>Файл открывается в Excel и других табличных редакторах.</small></section>
        </div>}
      </div>
    </main>

    {modal && <div className="backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setModal(''); }}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={modal === 'product' ? 'Форма товара' : 'Форма цены'}>
        <div className="dialog-head"><div><span>PRICEPILOT / ФОРМА</span><h2>{modal === 'product' ? editing ? 'Изменить товар' : 'Новый товар' : 'Добавить цену'}</h2></div><button className="close" onClick={() => setModal('')} aria-label="Закрыть">×</button></div>
        {modal === 'product'
          ? <ProductForm initial={editing} onSave={saveProduct} onCancel={() => setModal('')} />
          : <PriceForm products={products} selectedId={selectedId} onSave={savePrice} onCancel={() => setModal('')} />}
      </div>
    </div>}
  </div>;
}

export default function App() {
  const [session, setSession] = useState(undefined);
  const [problem, setProblem] = useState('');
  const [resetToken, setResetToken] = useState(() => new URLSearchParams(location.hash.slice(1)).get('reset'));
  useEffect(() => {
    if (resetToken) history.replaceState(null, '', location.pathname + location.search);
  }, [resetToken]);

  useEffect(() => {
    let active = true;
    const expired = () => setSession(null);
    window.addEventListener('price-analyzer:session-expired', expired);
    api.session().then(
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
    };
  }, []);

  if (session === undefined && !resetToken) {
    return <main className="account-shell"><p role="status">Проверяем сеанс…</p></main>;
  }
  if (!session || resetToken) {
    return <AccountForm resetToken={resetToken} onReset={() => { setResetToken(null); setSession(null); }}
      initialError={problem} onSession={setSession} />;
  }
  return <Workspace session={session} onLogout={() => setSession(null)} />;
}

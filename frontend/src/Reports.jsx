import { useState } from 'react';
import { api } from './lib/api.js';

const initialFilters = { group: 'category', from: '', to: '', category: '', store: '' };
const number = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export default function Reports({ products, prices }) {
  const [filters, setFilters] = useState(initialFilters);
  const [report, setReport] = useState(null);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const categories = [...new Set(products.map((product) => product.category))].sort();
  const stores = [...new Set(prices.map((price) => price.store))].sort();

  async function submit(event) {
    event.preventDefault();
    setPending(true);
    setError('');
    setReport(null);
    const active = Object.fromEntries(Object.entries(filters).filter(([, value]) => value));
    try {
      const rows = await api.report(active);
      setReport({ rows, query: new URLSearchParams(active).toString() });
    } catch (failure) {
      setError(failure.message);
    } finally {
      setPending(false);
    }
  }

  function change(event) {
    const { name, value } = event.target;
    setFilters((previous) => ({ ...previous, [name]: value }));
    setReport(null);
    setError('');
  }

  async function download(format) {
    setDownloading(true);
    setError('');
    try {
      await api.downloadReport(format, report.query);
    } catch (failure) {
      setError(failure.message);
    } finally {
      setDownloading(false);
    }
  }

  return <section className="panel reports-panel">
    <h2>Динамика цен</h2>
    <form className="form-grid" onSubmit={submit}>
      <fieldset disabled={pending} className="report-filters">
        <label><span id="report-group-label">Тип отчёта</span><select aria-labelledby="report-group-label" name="group" value={filters.group} onChange={change}>
          <option value="category">По категориям</option>
          <option value="store">По магазинам</option>
          <option value="product">По товарам</option>
        </select></label>
        <label>Начало периода<input type="date" name="from" value={filters.from} max={filters.to || undefined} onChange={change} /></label>
        <label>Конец периода<input type="date" name="to" value={filters.to} min={filters.from || undefined} onChange={change} /></label>
        <label><span id="report-category-label">Категория</span><select aria-labelledby="report-category-label" name="category" value={filters.category} onChange={change}>
          <option value="">Все категории</option>
          {categories.map((value) => <option key={value}>{value}</option>)}
        </select></label>
        <label><span id="report-store-label">Магазин</span><select aria-labelledby="report-store-label" name="store" value={filters.store} onChange={change}>
          <option value="">Все магазины</option>
          {stores.map((value) => <option key={value}>{value}</option>)}
        </select></label>
      </fieldset>
      <button className="btn primary" type="submit" disabled={pending}>{pending ? 'Расчёт…' : 'Сформировать отчёт'}</button>
    </form>
    {error && <p className="alert error" role="alert">{error}</p>}
    {report && <>
      <p className="helper">Изменение между первой и последней ценой товара в магазине. В среднем учитываются серии с двумя датами или больше.</p>
      {report.rows.length === 0 ? <p className="empty" role="status">За выбранный период недостаточно записей для расчёта.</p>
        : <div className="table-scroll"><table><thead><tr>
          <th scope="col">Группа</th><th scope="col">Серий</th>
          <th scope="col">Изменение, ₽</th><th scope="col">Изменение, %</th>
        </tr></thead><tbody>{report.rows.map((row) => <tr key={row.label}>
          <th scope="row">{row.label}</th><td>{row.observationSeries}</td>
          <td>{number.format(row.averageChange)}</td><td>{number.format(row.averagePercent)}</td>
        </tr>)}</tbody></table></div>}
      <div className="report-downloads">
        <button type="button" className="btn secondary download" disabled={downloading} onClick={() => download('pdf')}>Скачать PDF</button>
        <button type="button" className="btn secondary download" disabled={downloading} onClick={() => download('xlsx')}>Скачать Excel</button>
      </div>
    </>}
  </section>;
}

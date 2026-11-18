import { useEffect, useState } from 'react';
import { api } from './lib/api.js';

export default function AdminSettings() {
  const [days, setDays] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');

  useEffect(() => {
    let active = true;
    api.retention().then(({ days }) => { if (active) setDays(days); })
      .catch((failure) => { if (active) setError(failure.message); });
    return () => { active = false; };
  }, []);

  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    setStatus('');
    try {
      const current = await api.setRetention(Number(days));
      setDays(current.days);
      setStatus('Срок хранения сохранён.');
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return <section className="panel retention">
    <h2>Срок хранения истории цен</h2>
    <p id="retention-help">Записи старше указанного срока удаляются во время ежедневной очистки. 0 дней сохраняет всю историю.</p>
    {error && <div className="alert error" role="alert">{error}</div>}
    {days === null
      ? !error && <p role="status">Загрузка настроек…</p>
      : <form className="form-grid" onSubmit={save}>
          <label>Дней хранения<input type="number" name="days" required min="0" max="2147483647" step="1"
            aria-describedby="retention-help" value={days} onChange={(event) => setDays(event.target.value)} /></label>
          <button className="btn primary" disabled={busy}>{busy ? 'Сохранение…' : 'Сохранить срок'}</button>
        </form>}
    {status && <p role="status">{status}</p>}
  </section>;
}

import { useEffect, useState } from 'react';
import { api } from './lib/api.js';
import { formatPrice } from './lib/priceUtils.js';

const dateTime = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });

export function useUnreadNotifications() {
  const [summary, setSummary] = useState({ count: null, revision: 0 });
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    let controller;
    function fetchUnread() {
      if (document.visibilityState !== 'visible') return;
      controller?.abort();
      const request = new AbortController();
      controller = request;
      api.unreadNotifications(request.signal).then(({ count }) => {
        if (!request.signal.aborted) {
          setSummary((old) => ({ count, revision: old.revision + Number(old.count != null && count > old.count) }));
        }
      }).catch(() => { /* The next poll retries. Inbox requests show their own errors. */ });
    }
    fetchUnread();
    const timer = window.setInterval(fetchUnread, 45_000);
    document.addEventListener('visibilitychange', fetchUnread);
    return () => {
      controller?.abort();
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', fetchUnread);
    };
  }, [refresh]);

  return { ...summary, refresh: () => setRefresh((previous) => previous + 1) };
}

export function NotificationBell({ count, active, onOpen }) {
  return <button type="button" className="personal-icon notification-bell" onClick={onOpen}
    aria-label={count ? `Уведомления, непрочитанных: ${count}` : 'Уведомления'}
    aria-current={active ? 'page' : undefined}>
    <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7"
      strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" />
    </svg>
    {count > 0 && <span className="notification-count" aria-hidden="true">{count > 99 ? '99+' : count}</span>}
  </button>;
}

export default function Notifications({ revision, onRead, onFavorites }) {
  const [inbox, setInbox] = useState(null);
  const [attempt, setAttempt] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    const request = new AbortController();
    api.notifications(null, request.signal).then((value) => { if (!request.signal.aborted) setInbox(value); })
      .catch((failure) => { if (!request.signal.aborted) setError(failure.message); });
    return () => request.abort();
  }, [attempt, revision]);

  async function perform(action) {
    setBusy(true);
    setError('');
    try {
      await action();
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  function read(id) {
    perform(async () => {
      await api.readNotification(id);
      setInbox((previous) => ({
        ...previous, items: previous.items.map((notice) => notice.id === id ? { ...notice, read: true } : notice),
      }));
      onRead();
    });
  }

  function selectMode(mode) {
    perform(async () => {
      await api.setNotificationMode(mode);
      setInbox((previous) => ({ ...previous, mode }));
    });
  }

  function loadMore() {
    perform(async () => {
      const before = inbox.nextBefore;
      const next = await api.notifications(before);
      setInbox((previous) => previous.nextBefore === before
        ? { ...previous, nextBefore: next.nextBefore, items: [...previous.items, ...next.items] }
        : previous);
    });
  }

  return <>
    {error && <div role="alert" className="alert error">
      {error}<button type="button" onClick={() => { setError(''); setAttempt((value) => value + 1); }}>Обновить</button>
    </div>}
    {!inbox ? !error && <p role="status">Загрузка уведомлений…</p> : <>
      <section className="panel notification-settings">
        <div><h2>Какие изменения показывать</h2><p>Настройка действует на новые уведомления об избранных товарах.</p></div>
        <label>Получать уведомления
          <select name="notificationMode" value={inbox.mode} disabled={busy} onChange={(event) => selectMode(event.target.value)}>
            <option value="ALL">Все изменения цен</option>
            <option value="DECREASE_ONLY">Только снижение цены</option>
            <option value="OFF">Уведомления отключены</option>
          </select>
        </label>
      </section>
      <section className="panel inbox-panel">
        <h2>История уведомлений</h2>
        {!inbox.items.length ? <div className="personal-empty">
          <p>Здесь появятся изменения цен на товары, которые вы добавили в избранное.</p>
          <button className="btn secondary personal-control" onClick={onFavorites}>Открыть избранное</button>
        </div> : <ul className="inbox-list">
          {inbox.items.map((notice) => {
            const down = Number(notice.newPrice) < Number(notice.oldPrice);
            return <li key={notice.id} className={notice.read ? 'inbox-item' : 'inbox-item unread'}>
              <div className="inbox-description">
                <time dateTime={notice.createdAt}>{dateTime.format(new Date(notice.createdAt))}</time>
                <h3>{notice.productId == null ? notice.productName
                  : <a className="personal-control" href={`#product=${notice.productId}`}>{notice.productName}</a>}</h3>
                <p>{notice.store}</p>
                {!notice.read && <span className="inbox-unread">Непрочитано</span>}
              </div>
              <div className="inbox-change">
                <p className={down ? 'price-down' : 'price-up'}>
                  {down ? 'Цена снизилась' : 'Цена выросла'}
                </p>
                <div className="price-change"><span>{formatPrice(notice.oldPrice)}</span>
                  <span aria-hidden="true">→</span><strong>{formatPrice(notice.newPrice)}</strong></div>
                {!notice.read && <button className="btn secondary personal-control" disabled={busy}
                  onClick={() => read(notice.id)}>Отметить прочитанным</button>}
              </div>
            </li>;
          })}
        </ul>}
        {inbox.nextBefore != null && <button type="button" className="btn secondary personal-control"
          disabled={busy} onClick={loadMore}>Показать более ранние</button>}
      </section>
    </>}
  </>;
}

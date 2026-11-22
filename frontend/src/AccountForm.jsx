import { useEffect, useState } from 'react';
import { api } from './lib/api.js';
import InterfaceIcon from './InterfaceIcon.jsx';

const headings = {
  login: 'Вход в Price Analyzer',
  register: 'Создание аккаунта',
  request: 'Восстановление пароля',
  reset: 'Новый пароль',
};
const actions = {
  login: 'Войти',
  register: 'Зарегистрироваться',
  request: 'Отправить ссылку',
  reset: 'Сохранить новый пароль',
};

export default function AccountForm({ resetToken, initialError, onSession, onReset }) {
  const [mode, setMode] = useState(resetToken ? 'reset' : 'login');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(initialError);
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (resetToken) {
      setMode('reset');
      setError('');
      setNotice('');
    }
  }, [resetToken]);

  function changeMode(next) {
    setMode(next);
    setError('');
    setNotice('');
  }

  async function submit(event) {
    event.preventDefault();
    const form = Object.fromEntries(new FormData(event.currentTarget));
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (mode === 'request') {
        await api.requestReset(form);
        setNotice('На зарегистрированный адрес будет отправлена ссылка для восстановления.');
      } else if (mode === 'reset') {
        await api.resetPassword({ token: resetToken, password: form.password });
        onReset();
        setMode('login');
        setNotice('Пароль обновлён. Теперь войдите с новым паролем.');
      } else {
        if (mode === 'register') await api.register(form);
        onSession(await api.login(form));
      }
    } catch (failure) {
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return <main className="account-shell">
    <section className="panel account-panel" aria-labelledby="account-heading">
      <div className="account-brand">
        <span className="brand-mark"><InterfaceIcon name="tag" /></span>
        {mode !== 'login' && <b translate="no">Price Analyzer</b>}
      </div>
      <h1 id="account-heading">{headings[mode]}</h1>
      <form key={mode} className="form-grid" onSubmit={submit} aria-busy={busy}>
        {mode !== 'reset' && <label>Электронная почта<input name="email" type="email" autoComplete="email"
          maxLength="254" spellCheck={false} required /></label>}
        {mode !== 'request' && <label>Пароль<input name="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={mode === 'login' ? undefined : 12} required /></label>}
        {(mode === 'register' || mode === 'reset') && <p className="helper">Минимум 12 символов, максимум 72 байта.</p>}
        {error && <p className="field-error" role="alert">{error}</p>}
        {notice && <p className="alert success" role="status">{notice}</p>}
        <button className="btn primary" disabled={busy} type="submit">{busy ? 'Подождите…' : actions[mode]}</button>
      </form>
      <div className="account-actions">
        {mode === 'login' ? <>
          <button className="text-btn" disabled={busy} onClick={() => changeMode('register')}>Создать аккаунт</button>
          <button className="text-btn" disabled={busy} onClick={() => changeMode('request')}>Забыли пароль?</button>
        </> : <button className="text-btn" disabled={busy} onClick={() => { if (mode === 'reset') onReset(); changeMode('login'); }}>Вернуться ко входу</button>}
      </div>
    </section>
  </main>;
}

let auth = null;
let refreshing;
let cookieWrites = Promise.resolve();

function notify(name, detail) {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent(name, { detail }));
}

function updateAuth(next) {
  const previous = auth;
  auth = next;
  if (!next && previous) notify('price-analyzer:session-expired');
  if (next && (previous?.user.email !== next.user.email || previous?.user.role !== next.user.role)) {
    notify('price-analyzer:session-changed', next.user);
  }
  return next;
}

function cookieOperation(task) {
  const next = cookieWrites.then(() => {
    // The cookie is shared between tabs. Serialize writes when the browser supports Web Locks.
    const locks = globalThis.navigator?.locks;
    return locks ? locks.request('price-analyzer-auth', task) : task();
  });
  cookieWrites = next.catch(() => {});
  return next;
}

function fetchApi(path, options = {}, accessToken) {
  return fetch(`/api${path}`, {
    ...options,
    headers: {
      ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
    },
  });
}

async function parse(response, binary = false) {
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw Object.assign(new Error(payload.error || `Ошибка сервера (HTTP ${response.status})`), { status: response.status });
  }
  if (response.status === 204) return null;
  if (binary) return response.blob();
  const type = response.headers.get('content-type') || '';
  return type.includes('application/json') ? response.json() : response.text();
}

async function browserAuth(path, values) {
  return parse(await fetchApi(`/auth/${path}`, {
    method: 'POST', headers: { 'X-Price-Auth': '1' },
    body: values === undefined ? undefined : JSON.stringify(values),
  }));
}

function refreshAuth() {
  if (refreshing) return refreshing;
  const previous = auth;
  refreshing = cookieOperation(async () => {
    if (auth !== previous) return auth;
    try {
      return updateAuth(await browserAuth('refresh'));
    } catch (error) {
      if (error.status === 401) updateAuth(null);
      throw error;
    }
  }).finally(() => { refreshing = undefined; });
  return refreshing;
}

export async function request(path, options = {}, binary = false) {
  const previous = auth;
  let response = await fetchApi(path, options, previous?.accessToken);
  if (response.status !== 401 || !previous) return parse(response, binary);

  options.signal?.throwIfAborted();
  const next = auth === previous ? await refreshAuth() : auth;
  options.signal?.throwIfAborted();
  // A different account may have logged in in another tab. Do not replay its predecessor's request.
  if (!next || next.user.email !== previous.user.email) return parse(response, binary);

  response = await fetchApi(path, options, next.accessToken);
  if (response.status === 401 && auth === next) updateAuth(null);
  return parse(response, binary);
}

function mutate(path, options = {}) {
  return request(path, { method: 'POST', ...options });
}

async function download(path, filename) {
  const blob = await request(path, {}, true);
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export const api = {
  restoreSession: async () => auth?.user ?? (await refreshAuth())?.user ?? null,
  register: async (value) => parse(await fetchApi('/auth/register', { method: 'POST', body: JSON.stringify(value) })),
  login: (value) => cookieOperation(async () => updateAuth(await browserAuth('login', value)).user),
  logout: () => cookieOperation(async () => { await browserAuth('logout'); updateAuth(null); }),
  requestReset: async (value) => parse(await fetchApi('/auth/password/request', { method: 'POST', body: JSON.stringify(value) })),
  resetPassword: (value) => cookieOperation(async () => { await browserAuth('password/reset', value); updateAuth(null); }),
  health: async () => parse(await fetchApi('/health')),
  downloadCsv: (id) => download(`/export/prices.csv${id ? `?productId=${encodeURIComponent(id)}` : ''}`, 'prices.csv'),
  downloadReport: (format, query) => download(`/reports.${format}?${query}`, `price-report.${format}`),
  products: () => request('/products'),
  favorites: (signal) => request('/favorites', { signal }),
  setFavorite: (id, saved) => mutate(`/favorites/${id}`, { method: saved ? 'PUT' : 'DELETE' }),
  notifications: (before, signal) => request(`/notifications${before == null ? '' : `?before=${before}`}`, { signal }),
  unreadNotifications: (signal) => request('/notifications/unread', { signal }),
  readNotification: (id) => mutate(`/notifications/${id}/read`, { method: 'PUT' }),
  setNotificationMode: (mode) => mutate('/notifications/settings', { method: 'PUT', body: JSON.stringify({ mode }) }),
  createProduct: (value) => mutate('/products', { method: 'POST', body: JSON.stringify(value) }),
  updateProduct: (id, value) => mutate(`/products/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
  deleteProduct: (id) => mutate(`/products/${id}`, { method: 'DELETE' }),
  prices: (productId) => request(`/prices${productId ? `?productId=${productId}` : ''}`),
  createPrice: (value) => mutate('/prices', { method: 'POST', body: JSON.stringify(value) }),
  updatePrice: (id, price) => mutate(`/prices/${id}`, { method: 'PUT', body: JSON.stringify({ price }) }),
  deletePrice: (id) => mutate(`/prices/${id}`, { method: 'DELETE' }),
  retention: () => request('/admin/settings/retention'),
  setRetention: (days) => mutate('/admin/settings/retention', { method: 'PUT', body: JSON.stringify({ days }) }),
  analysis: (id, from = '', to = '', signal) => {
    const query = new URLSearchParams();
    if (from) query.set('from', from);
    if (to) query.set('to', to);
    return request(`/analysis/${id}${query.size ? `?${query}` : ''}`, { signal });
  },
  importCsv: (text) => mutate('/import/prices', {
    method: 'POST', body: text, headers: { 'Content-Type': 'text/csv' },
  }),
  report: (filters) => request(`/reports?${new URLSearchParams(filters)}`),
  importXlsx: (file) => {
    const body = new FormData();
    body.set('file', file);
    return mutate('/import/prices.xlsx', { body });
  },
};

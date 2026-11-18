/** Централизованный доступ к backend API. */
export async function request(path, options = {}) {
  const result = await fetch(`/api${path}`, {
    ...options,
    headers: {
      ...(options.body && !(options.body instanceof FormData)
        ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  });
  if (!result.ok) {
    const payload = await result.json().catch(() => ({}));
    const error = new Error(payload.error || `Ошибка сервера (HTTP ${result.status})`);
    error.status = result.status;
    if (result.status === 401 && !path.startsWith('/auth/') && typeof window !== 'undefined') {
      window.dispatchEvent(new Event('price-analyzer:session-expired'));
    }
    throw error;
  }
  if (result.status === 204) return null;
  const contentType = result.headers.get('content-type') || '';
  return contentType.includes('application/json') ? result.json() : result.text();
}

async function mutate(path, options = {}) {
  const { headerName, token } = await request('/auth/csrf');
  return request(path, { method: 'POST', ...options, headers: { ...options.headers, [headerName]: token } });
}

export const api = {
  session: () => request('/auth/me'),
  register: (value) => mutate('/auth/register', { body: JSON.stringify(value) }),
  login: (value) => mutate('/auth/login', {
    body: new URLSearchParams(value),
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  }),
  logout: () => mutate('/auth/logout'),
  requestReset: (value) => mutate('/auth/password/request', { body: JSON.stringify(value) }),
  resetPassword: (value) => mutate('/auth/password/reset', { body: JSON.stringify(value) }),
  health: () => request('/health'),
  products: () => request('/products'),
  createProduct: (value) => mutate('/products', { method: 'POST', body: JSON.stringify(value) }),
  updateProduct: (id, value) => mutate(`/products/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
  deleteProduct: (id) => mutate(`/products/${id}`, { method: 'DELETE' }),
  prices: (productId) => request(`/prices${productId ? `?productId=${productId}` : ''}`),
  createPrice: (value) => mutate('/prices', { method: 'POST', body: JSON.stringify(value) }),
  deletePrice: (id) => mutate(`/prices/${id}`, { method: 'DELETE' }),
  retention: () => request('/admin/settings/retention'),
  setRetention: (days) => mutate('/admin/settings/retention', { method: 'PUT', body: JSON.stringify({ days }) }),
  analysis: (id, from = '', to = '') => {
    const query = new URLSearchParams();
    if (from) query.set('from', from);
    if (to) query.set('to', to);
    return request(`/analysis/${id}${query.size ? `?${query}` : ''}`);
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

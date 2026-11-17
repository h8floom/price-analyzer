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
    throw new Error(payload.error || `Ошибка сервера (HTTP ${result.status})`);
  }
  if (result.status === 204) return null;
  const contentType = result.headers.get('content-type') || '';
  return contentType.includes('application/json') ? result.json() : result.text();
}

export const api = {
  health: () => request('/health'),
  products: () => request('/products'),
  createProduct: (value) => request('/products', { method: 'POST', body: JSON.stringify(value) }),
  updateProduct: (id, value) => request(`/products/${id}`, { method: 'PUT', body: JSON.stringify(value) }),
  deleteProduct: (id) => request(`/products/${id}`, { method: 'DELETE' }),
  prices: (productId) => request(`/prices${productId ? `?productId=${productId}` : ''}`),
  createPrice: (value) => request('/prices', { method: 'POST', body: JSON.stringify(value) }),
  deletePrice: (id) => request(`/prices/${id}`, { method: 'DELETE' }),
  analysis: (id, from = '', to = '') => {
    const query = new URLSearchParams();
    if (from) query.set('from', from);
    if (to) query.set('to', to);
    return request(`/analysis/${id}${query.size ? `?${query}` : ''}`);
  },
  importCsv: (text) => request('/import/prices', {
    method: 'POST', body: text, headers: { 'Content-Type': 'text/csv' },
  }),
};

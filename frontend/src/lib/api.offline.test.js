import test from 'node:test';
import assert from 'node:assert/strict';
import { api, request } from './api.js';

test('health parses JSON response', async () => {
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      assert.equal(url, '/api/health');
      return {
        ok: true, status: 200,
        headers: { get: () => 'application/json' },
        json: async () => ({ status: 'ok' }),
      };
    };
    assert.deepEqual(await api.health(), { status: 'ok' });
  } finally { globalThis.fetch = oldFetch; }
});

test('POST sends correct JSON and handles created response', async () => {
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      if (url.endsWith('/csrf')) return { ok: true, status: 200, headers: { get: () => 'application/json' },
          json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) };
      assert.equal(url, '/api/products');
      assert.equal(options.headers['X-CSRF-TOKEN'], 'test-token');
      assert.equal(options.method, 'POST');
      assert.equal(options.headers['Content-Type'], 'application/json');
      assert.deepEqual(JSON.parse(options.body), { name: 'Кофе', category: 'Напитки', unit: 'шт.' });
      return { ok: true, status: 201, headers: { get: () => 'application/json' }, json: async () => ({ id: 10 }) };
    };
    assert.equal((await api.createProduct({ name: 'Кофе', category: 'Напитки', unit: 'шт.' })).id, 10);
  } finally { globalThis.fetch = oldFetch; }
});

test('API error becomes readable Error', async () => {
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => ({ ok: false, status: 409, json: async () => ({ error: 'Дубликат' }) });
    await assert.rejects(request('/products'), /Дубликат/);
  } finally { globalThis.fetch = oldFetch; }
});

test('no-content response is null', async () => {
  const oldFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => url.endsWith('/csrf') ? { ok: true, status: 200, headers: { get: () => 'application/json' },
          json: async () => ({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) } : { ok: true, status: 204 };
    assert.equal(await api.deleteProduct(1), null);
  } finally { globalThis.fetch = oldFetch; }
});

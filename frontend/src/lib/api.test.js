import { describe, expect, it, vi, afterEach } from 'vitest';
import { api, request } from './api.js';

afterEach(() => vi.unstubAllGlobals());

function response(body, status = 200, type = 'application/json') {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => type },
    json: async () => body,
    text: async () => String(body),
  };
}

describe('API transport', () => {
  it('loads products from the expected API path', async () => {
    const fetchMock = vi.fn(async () => response([{ id: 7, name: 'Кофе' }]));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api.products()).resolves.toEqual([{ id: 7, name: 'Кофе' }]);
    expect(fetchMock).toHaveBeenCalledWith('/api/products', expect.any(Object));
  });

  it('encodes date parameters for comparison', async () => {
    const fetchMock = vi.fn(async () => response({ history: [] }));
    vi.stubGlobal('fetch', fetchMock);
    await api.analysis(8, '2026-10-01', '2026-10-31');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/analysis/8?from=2026-10-01&to=2026-10-31');
  });

  it('sends CSV in text/csv format, not as JSON', async () => {
    const fetchMock = vi.fn(async (url) => url.endsWith('/csrf')
      ? response({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) : response({ imported: 1 }));
    vi.stubGlobal('fetch', fetchMock);
    await api.importCsv('productId,store,price,date\n');
    const [, options] = fetchMock.mock.calls[1];
    expect(options.headers['X-CSRF-TOKEN']).toBe('test-token');
    expect(options.headers['Content-Type']).toBe('text/csv');
    expect(options.body).toContain('productId,store');
  });

  it('uploads XLSX as multipart with a CSRF header', async () => {
    const fetchMock = vi.fn(async (url) => url.endsWith('/csrf')
      ? response({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) : response({ imported: 1 }));
    vi.stubGlobal('fetch', fetchMock);
    const file = new File(['fixture'], 'prices.xlsx');
    await expect(api.importXlsx(file)).resolves.toEqual({ imported: 1 });
    const [path, options] = fetchMock.mock.calls[1];
    expect(path).toBe('/api/import/prices.xlsx');
    expect(options.body.get('file')).toBe(file);
    expect(options.headers).toEqual({ 'X-CSRF-TOKEN': 'test-token' });
  });

  it('reads errors from the API and supports 204', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ error: 'Конфликт' }, 409)));
    await expect(request('/products')).rejects.toThrow('Конфликт');
    vi.stubGlobal('fetch', vi.fn(async (url) => url.endsWith('/csrf')
      ? response({ headerName: 'X-CSRF-TOKEN', token: 'test-token' }) : response(null, 204)));
    await expect(api.deletePrice(10)).resolves.toBeNull();
  });

  it('uses fallback message when server error body is not JSON', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => { throw Error('not JSON'); } })));
    await expect(request('/broken')).rejects.toThrow('HTTP 500');
  });
});

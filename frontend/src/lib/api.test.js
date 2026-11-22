import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

let api;
let request;
const user = { email: 'student@example.test', role: 'USER' };

beforeEach(async () => {
  vi.resetModules();
  ({ api, request } = await import('./api.js'));
});
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

function mockApi(handler) {
  const fetchMock = vi.fn(async (path, options) => path.endsWith('/auth/login')
    ? response({ accessToken: 'fixture-access', user }) : handler(path, options));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

async function login() {
  return api.login({ email: user.email, password: 'not-a-real-password' });
}

describe('API transport', () => {
  it('loads products and passes the access token after login', async () => {
    const fetchMock = mockApi(() => response([{ id: 7, name: 'Кофе' }]));
    await expect(login()).resolves.toEqual(user);
    await expect(api.products()).resolves.toEqual([{ id: 7, name: 'Кофе' }]);
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST', headers: { 'X-Price-Auth': '1', 'Content-Type': 'application/json' },
    }));
    expect(fetchMock).toHaveBeenLastCalledWith('/api/products', expect.objectContaining({
      headers: { Authorization: 'Bearer fixture-access' },
    }));
  });

  it('encodes date parameters for comparison', async () => {
    const fetchMock = mockApi(() => response({ history: [] }));
    await api.analysis(8, '2026-10-01', '2026-10-31');
    expect(fetchMock.mock.calls[0][0]).toBe('/api/analysis/8?from=2026-10-01&to=2026-10-31');
  });

  it('sends CSV in text/csv format with the access token', async () => {
    const fetchMock = mockApi(() => response({ imported: 1 }));
    await login();
    await api.importCsv('productId,store,price,date\n');
    const [, options] = fetchMock.mock.calls[1];
    expect(options.headers.Authorization).toBe('Bearer fixture-access');
    expect(options.headers['Content-Type']).toBe('text/csv');
    expect(options.body).toContain('productId,store');
  });

  it('uploads XLSX as multipart with the access token', async () => {
    const fetchMock = mockApi(() => response({ imported: 1 }));
    await login();
    const file = new File(['fixture'], 'prices.xlsx');
    await expect(api.importXlsx(file)).resolves.toEqual({ imported: 1 });
    const [path, options] = fetchMock.mock.calls[1];
    expect(path).toBe('/api/import/prices.xlsx');
    expect(options.body.get('file')).toBe(file);
    expect(options.headers).toEqual({ Authorization: 'Bearer fixture-access' });
  });

  it('reads errors from the API and supports 204', async () => {
    mockApi((path) => path.endsWith('/products') ? response({ error: 'Конфликт' }, 409) : response(null, 204));
    await expect(request('/products')).rejects.toThrow('Конфликт');
    await expect(api.deletePrice(10)).resolves.toBeNull();
  });

  it('uses fallback message when server error body is not JSON', async () => {
    mockApi(() => ({ ok: false, status: 500, json: async () => { throw Error('not JSON'); } }));
    await expect(request('/broken')).rejects.toThrow('HTTP 500');
  });

  it('sends personalized requests without any client-supplied account ID', async () => {
    const fetchMock = mockApi(() => response(null, 204));
    await login();
    await api.favorites();
    await api.setFavorite(7, true);
    await api.setFavorite(7, false);
    await api.notifications(12);
    await api.unreadNotifications();
    await api.readNotification(15);
    await api.setNotificationMode('DECREASE_ONLY');

    const requests = fetchMock.mock.calls.slice(1);
    expect(requests.map(([path, { method }]) => [path, method])).toEqual([
      ['/api/favorites', undefined], ['/api/favorites/7', 'PUT'], ['/api/favorites/7', 'DELETE'],
      ['/api/notifications?before=12', undefined], ['/api/notifications/unread', undefined],
      ['/api/notifications/15/read', 'PUT'], ['/api/notifications/settings', 'PUT'],
    ]);
    expect(requests.every(([, { headers }]) => headers.Authorization === 'Bearer fixture-access')).toBe(true);
    expect(requests.at(-1)[1].body).toBe('{"mode":"DECREASE_ONLY"}');
  });

  it('restores after reload and deduplicates concurrent 401 refreshes including writes', async () => {
    let rotations = 0;
    const fetchMock = mockApi((path, options) => {
      if (path.endsWith('/auth/refresh')) {
        expect(options.headers['X-Price-Auth']).toBe('1');
        return response({ accessToken: `fixture-refresh-${++rotations}`, user });
      }
      return options.headers.Authorization === 'Bearer fixture-refresh-2'
        ? response({ saved: true }) : response({}, 401);
    });
    await expect(api.restoreSession()).resolves.toEqual(user);
    await expect(Promise.all([api.products(), api.createProduct({ name: 'Кофе' })]))
      .resolves.toEqual([{ saved: true }, { saved: true }]);
    expect(rotations).toBe(2);
    expect(fetchMock.mock.calls.filter(([path, options]) => path.endsWith('/products') && options.method === 'POST')
      .map(([, options]) => JSON.parse(options.body))).toEqual([{ name: 'Кофе' }, { name: 'Кофе' }]);
  });

  it('does not retry 403 and stops on the second 401', async () => {
    const fetchMock = mockApi((path) => {
      if (path.endsWith('/auth/refresh')) return response({ accessToken: 'fixture-renewed', user });
      return path.includes('/admin/') ? response({}, 403) : response({}, 401);
    });
    await login();
    await expect(api.retention()).rejects.toMatchObject({ status: 403 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await expect(api.products()).rejects.toMatchObject({ status: 401 });
    expect(fetchMock.mock.calls.slice(2).map(([path]) => path))
      .toEqual(['/api/products', '/api/auth/refresh', '/api/products']);
  });

  it('does not replay a request under an account obtained from another tab', async () => {
    const fetchMock = mockApi((path) => path.endsWith('/auth/refresh')
      ? response({ accessToken: 'another-fixture', user: { ...user, email: 'other@example.test' } })
      : response({}, 401));
    await login();
    await expect(api.deletePrice(15)).rejects.toMatchObject({ status: 401 });
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual([
      '/api/auth/login', '/api/prices/15', '/api/auth/refresh',
    ]);
  });

  it('clears local credentials only after successful server logout', async () => {
    let attempts = 0;
    const fetchMock = mockApi((path) => path.endsWith('/auth/logout')
      ? response({}, ++attempts < 2 ? 503 : 204) : response([]));
    await login();
    await expect(api.logout()).rejects.toMatchObject({ status: 503 });
    await api.products();
    expect(fetchMock.mock.calls.at(-1)[1].headers.Authorization).toBe('Bearer fixture-access');
    await api.logout();
    await api.products();
    expect(fetchMock.mock.calls.at(-1)[1].headers.Authorization).toBeUndefined();
  });
});

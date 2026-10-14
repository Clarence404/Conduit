import { beforeEach, describe, expect, it, vi } from 'vitest'
import MockAdapter from 'axios-mock-adapter'
import { createHttp, REFRESH_URL, type TokenHolder } from './http'

function holder(initial: string | null) {
  let token = initial
  const h: TokenHolder & { onAuthFailed: ReturnType<typeof vi.fn> } = {
    getToken: () => token,
    setToken: (t) => {
      token = t
    },
    onAuthFailed: vi.fn(),
  }
  return h
}

describe('createHttp', () => {
  let tokens: ReturnType<typeof holder>
  let http: ReturnType<typeof createHttp>
  let mock: MockAdapter

  beforeEach(() => {
    tokens = holder('old')
    http = createHttp(tokens)
    mock = new MockAdapter(http)
  })

  it('attaches the bearer token', async () => {
    mock.onGet('/api/v1/x').reply((config) => [200, { auth: config.headers?.Authorization }])

    const res = await http.get('/api/v1/x')

    expect(res.data.auth).toBe('Bearer old')
  })

  it('refreshes once on 401 and retries with the new token', async () => {
    mock.onGet('/api/v1/x').reply((config) =>
      config.headers?.Authorization === 'Bearer new' ? [200, { ok: true }] : [401],
    )
    mock.onPost(REFRESH_URL).reply(200, { accessToken: 'new' })

    const res = await http.get('/api/v1/x')

    expect(res.data.ok).toBe(true)
    expect(tokens.getToken()).toBe('new')
  })

  it('shares a single refresh between concurrent 401s', async () => {
    let refreshCalls = 0
    mock.onGet(/\/api\/v1\/(a|b)/).reply((config) =>
      config.headers?.Authorization === 'Bearer new' ? [200, {}] : [401],
    )
    mock.onPost(REFRESH_URL).reply(() => {
      refreshCalls++
      return [200, { accessToken: 'new' }]
    })

    await Promise.all([http.get('/api/v1/a'), http.get('/api/v1/b')])

    expect(refreshCalls).toBe(1)
  })

  it('gives up and notifies when refresh fails', async () => {
    mock.onGet('/api/v1/x').reply(401)
    mock.onPost(REFRESH_URL).reply(401)

    await expect(http.get('/api/v1/x')).rejects.toBeTruthy()

    expect(tokens.onAuthFailed).toHaveBeenCalledOnce()
    expect(tokens.getToken()).toBeNull()
  })

  it('does not try to refresh for auth endpoints', async () => {
    let refreshCalls = 0
    mock.onPost('/api/v1/auth/login').reply(401, { detail: '用户名或密码错误' })
    mock.onPost(REFRESH_URL).reply(() => {
      refreshCalls++
      return [200, { accessToken: 'new' }]
    })

    await expect(http.post('/api/v1/auth/login', {})).rejects.toBeTruthy()

    expect(refreshCalls).toBe(0)
    expect(tokens.onAuthFailed).not.toHaveBeenCalled()
  })

  it('notifies only once when concurrent requests all fail to refresh', async () => {
    mock.onGet(/\/api\/v1\/(a|b|c)/).reply(401)
    mock.onPost(REFRESH_URL).reply(401)

    const results = await Promise.allSettled([http.get('/api/v1/a'), http.get('/api/v1/b'), http.get('/api/v1/c')])

    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(tokens.onAuthFailed).toHaveBeenCalledOnce()
    expect(tokens.getToken()).toBeNull()
  })

  it('rejects without logging out when refresh fails with a network error', async () => {
    mock.onGet('/api/v1/x').reply(401)
    mock.onPost(REFRESH_URL).networkError()

    await expect(http.get('/api/v1/x')).rejects.toBeTruthy()

    expect(tokens.onAuthFailed).not.toHaveBeenCalled()
    expect(tokens.getToken()).toBe('old')
  })
})

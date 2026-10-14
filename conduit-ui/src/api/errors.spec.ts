import { describe, expect, it } from 'vitest'
import { AxiosError, type AxiosResponse } from 'axios'
import { problemMessage } from './errors'

function axiosError(response?: Partial<AxiosResponse>) {
  const error = new AxiosError('boom')
  if (response) {
    error.response = response as AxiosResponse
  }
  return error
}

describe('problemMessage', () => {
  it('uses ProblemDetail detail when present', () => {
    expect(problemMessage(axiosError({ status: 409, data: { detail: '资源已存在' } }))).toBe('资源已存在')
  })

  it('falls back to status code', () => {
    expect(problemMessage(axiosError({ status: 500, data: {} }))).toBe('请求失败（500）')
  })

  it('reports network errors', () => {
    expect(problemMessage(axiosError())).toBe('无法连接服务器')
  })

  it('handles plain errors', () => {
    expect(problemMessage(new Error('x'))).toBe('x')
    expect(problemMessage('weird')).toBe('未知错误')
  })
})

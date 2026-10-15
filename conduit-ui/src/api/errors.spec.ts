import { describe, expect, it } from 'vitest'
import { AxiosError, type AxiosResponse } from 'axios'
import { problemErrors, problemMessage } from './errors'

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

describe('problemErrors', () => {
  it('extracts the errors list of a ProblemDetail', () => {
    const error = axiosError({
      status: 400,
      data: { detail: '导入失败', errors: [{ identifier: 'Ua', message: '数据类型不可修改' }, { identifier: null, message: '超过上限' }] },
    })
    expect(problemErrors(error)).toEqual([
      { identifier: 'Ua', message: '数据类型不可修改' },
      { identifier: null, message: '超过上限' },
    ])
  })

  it('returns an empty list when there is no errors array', () => {
    expect(problemErrors(axiosError({ status: 400, data: { detail: 'x' } }))).toEqual([])
    expect(problemErrors(new Error('x'))).toEqual([])
  })
})

import { describe, expect, it } from 'vitest'
import {
  DEFAULT_EXECUTION_API_URL,
  DEFAULT_SYNC_WS_URL,
  getClientConfig,
} from './config'

describe('getClientConfig', () => {
  it('returns default service addresses when environment variables are unset', () => {
    const config = getClientConfig({})
    expect(config.syncWsUrl).toBe(DEFAULT_SYNC_WS_URL)
    expect(config.executionApiUrl).toBe(DEFAULT_EXECUTION_API_URL)
  })

  it('uses configured service addresses from environment when provided', () => {
    const customConfig = getClientConfig({
      VITE_SYNC_WS_URL: 'ws://custom-sync.example.com:8081/ws/rooms',
      VITE_EXECUTION_API_URL: 'https://custom-exec.example.com:8082',
    })
    expect(customConfig.syncWsUrl).toBe('ws://custom-sync.example.com:8081/ws/rooms')
    expect(customConfig.executionApiUrl).toBe('https://custom-exec.example.com:8082')
  })

  it('falls back to default if custom env values are empty strings', () => {
    const fallbackConfig = getClientConfig({
      VITE_SYNC_WS_URL: '',
      VITE_EXECUTION_API_URL: '',
    })
    expect(fallbackConfig.syncWsUrl).toBe(DEFAULT_SYNC_WS_URL)
    expect(fallbackConfig.executionApiUrl).toBe(DEFAULT_EXECUTION_API_URL)
  })
})

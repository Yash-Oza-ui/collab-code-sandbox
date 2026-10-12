/**
 * Client Configuration Module (T-01, T-06)
 *
 * Provides service addresses and settings sourced from environment variables
 * with defaults specified in docs/api-contracts.md (T-06).
 *
 * In Vite, environment variables exposed to the client are prefixed with VITE_.
 * Default ports and paths:
 * - sync-service: ws://localhost:8081/ws/rooms (T-06 §3.1)
 * - execution-service: http://localhost:8082 (T-06 §4.1)
 */

export const DEFAULT_SYNC_WS_URL = 'ws://localhost:8081/ws/rooms'
export const DEFAULT_EXECUTION_API_URL = 'http://localhost:8082'

export interface ClientConfig {
  syncWsUrl: string
  executionApiUrl: string
}

export function getClientConfig(
  env: Record<string, string | undefined> = import.meta.env,
): ClientConfig {
  return {
    syncWsUrl: env.VITE_SYNC_WS_URL || DEFAULT_SYNC_WS_URL,
    executionApiUrl: env.VITE_EXECUTION_API_URL || DEFAULT_EXECUTION_API_URL,
  }
}

export const config: ClientConfig = getClientConfig()

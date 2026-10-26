/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SYNC_WS_URL?: string
  readonly VITE_EXECUTION_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

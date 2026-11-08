
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

const monacoEditorApi = fileURLToPath(
  new URL(
    './node_modules/monaco-editor/esm/vs/editor/editor.api.js',
    import.meta.url,
  ),
)

export default defineConfig({
  plugins: [react()],

  resolve: {
    alias: {
      'monaco-editor/esm/vs/editor/editor.api.js': monacoEditorApi,
    },
  },

  test: {
    server: {
      deps: {
        inline: ['y-monaco'],
      },
    },
  },

  server: { port: 3000, strictPort: true },
  preview: { port: 3000, strictPort: true },
})

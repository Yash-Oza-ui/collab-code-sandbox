
import react from '@vitejs/plugin-react'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// Resolve y-monaco's deep Monaco import directly to the installed file.
const monacoEditorApi = fileURLToPath(
  new URL(
    './node_modules/monaco-editor/esm/vs/editor/editor.api.js',
    import.meta.url,
  ),
)

export default defineConfig({
  plugins: [react()],

  resolve: {
    alias: [
      {
        find: /^monaco-editor\/esm\/vs\/editor\/editor\.api\.js$/,
        replacement: monacoEditorApi,
      },
    ],
  },

  test: {
    environment: 'happy-dom',
    environmentOptions: {
      happyDOM: {
        url: 'http://localhost:3000',
      },
    },
    server: {
      deps: {
        inline: ['y-monaco'],
      },
    },
  },

  server: { port: 3000, strictPort: true },
  preview: { port: 3000, strictPort: true },
})

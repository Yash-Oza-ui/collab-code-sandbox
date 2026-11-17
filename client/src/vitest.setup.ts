/**
 * Test setup: make sure `localStorage` and `sessionStorage` work in every Node version.
 *
 * Newer Node versions (25 and later) define their own global `localStorage`. Without the
 * `--localstorage-file` flag it is not usable (Node prints "localStorage is not available
 * because --localstorage-file was not provided"), and it hides the storage of the test
 * DOM. Code that reads `localStorage.getItem(...)`, for example `getSessionId()` in a run
 * request, then fails with "Cannot read properties of undefined (reading 'getItem')".
 *
 * When the global storage is missing or unusable, this file installs a simple in-memory
 * storage instead. A storage that already works (older Node versions) is left untouched.
 */

type StorageName = 'localStorage' | 'sessionStorage'

function createMemoryStorage(): Storage {
  const data = new Map<string, string>()
  return {
    get length() {
      return data.size
    },
    clear: () => data.clear(),
    getItem: (key: string) => (data.has(key) ? (data.get(key) as string) : null),
    key: (index: number) => Array.from(data.keys())[index] ?? null,
    removeItem: (key: string) => {
      data.delete(key)
    },
    setItem: (key: string, value: string) => {
      data.set(key, String(value))
    },
  }
}

function isUsable(name: StorageName): boolean {
  try {
    const storage = (globalThis as unknown as Record<string, Storage | undefined>)[name]
    return (
      !!storage && typeof storage.getItem === 'function' && typeof storage.setItem === 'function'
    )
  } catch {
    return false
  }
}

for (const name of ['localStorage', 'sessionStorage'] as const) {
  if (!isUsable(name)) {
    Object.defineProperty(globalThis, name, {
      value: createMemoryStorage(),
      configurable: true,
      writable: true,
    })
  }
}

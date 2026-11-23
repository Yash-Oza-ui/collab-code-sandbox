import { describe, expect, it } from 'vitest'
import {
  buildZipArchive,
  createZipBlob,
  crc32,
  EXPORT_ZIP_FILENAME,
  readZipEntries,
  sanitizeAndValidateZipEntryName,
} from './exportZip'

describe('exportZip', () => {
  describe('CRC-32 calculation', () => {
    it('computes correct standard CRC-32 checksums', () => {
      const textEncoder = new TextEncoder()
      // Standard test vector: "123456789" -> 0xcbf43926 (3421760294)
      expect(crc32(textEncoder.encode('123456789'))).toBe(0xcbf43926)
      // Empty byte array -> 0
      expect(crc32(new Uint8Array(0))).toBe(0)
    })
  })

  describe('sanitizeAndValidateZipEntryName', () => {
    it('accepts valid coursework module names', () => {
      expect(sanitizeAndValidateZipEntryName('main.py', 'python').valid).toBe(true)
      expect(sanitizeAndValidateZipEntryName('utils.py', 'python').valid).toBe(true)
      expect(sanitizeAndValidateZipEntryName('Main.java', 'java').valid).toBe(true)
      expect(sanitizeAndValidateZipEntryName('app.js', 'javascript').valid).toBe(true)
      expect(sanitizeAndValidateZipEntryName('solver.cpp', 'cpp').valid).toBe(true)
    })

    it('refuses names with leading slashes', () => {
      const res = sanitizeAndValidateZipEntryName('/main.py', 'python')
      expect(res.valid).toBe(false)
      expect(res.error).toContain('Leading slashes not allowed')
    })

    it('refuses names with directory traversal (..)', () => {
      const res = sanitizeAndValidateZipEntryName('../secret.py', 'python')
      expect(res.valid).toBe(false)
      expect(res.error).toContain('Directory traversal not allowed')
    })

    it('refuses names with folder paths', () => {
      const res1 = sanitizeAndValidateZipEntryName('folder/main.py', 'python')
      expect(res1.valid).toBe(false)
      expect(res1.error).toContain('Folder parts not allowed')

      const res2 = sanitizeAndValidateZipEntryName('sub\\main.py', 'python')
      expect(res2.valid).toBe(false)
      expect(res2.error).toContain('Folder parts not allowed')
    })

    it('refuses names with wrong coursework extensions (DR4)', () => {
      const res = sanitizeAndValidateZipEntryName('main.js', 'python')
      expect(res.valid).toBe(false)
      expect(res.error).toContain("does not match language 'python'")
    })

    it('refuses duplicate filenames in the same export', () => {
      const existing = new Set<string>(['main.py'])
      const res = sanitizeAndValidateZipEntryName('main.py', 'python', existing)
      expect(res.valid).toBe(false)
      expect(res.error).toContain('Duplicate filename in export')
    })
  })

  describe('buildZipArchive and readZipEntries', () => {
    it('creates a valid single-file ZIP archive for Pair mode', () => {
      const files = [{ name: 'main.py', content: 'print("hello world")\n' }]
      const zipBytes = buildZipArchive(files, 'python')
      expect(zipBytes.length).toBeGreaterThan(0)

      // Unzip and inspect
      const entries = readZipEntries(zipBytes)
      expect(entries).toHaveLength(1)
      expect(entries[0].name).toBe('main.py')
      expect(entries[0].content).toBe('print("hello world")\n')
    })

    it('creates a valid multi-module ZIP archive for Open mode', () => {
      const files = [
        { name: 'main.py', content: 'import util\nprint(util.calc(10))\n' },
        { name: 'util.py', content: 'def calc(x):\n    return x * 2\n' },
        { name: 'models.py', content: 'class Model:\n    pass\n' },
      ]
      const zipBytes = buildZipArchive(files, 'python')
      const entries = readZipEntries(zipBytes)

      expect(entries).toHaveLength(3)
      expect(entries[0]).toEqual({ name: 'main.py', content: files[0].content })
      expect(entries[1]).toEqual({ name: 'util.py', content: files[1].content })
      expect(entries[2]).toEqual({ name: 'models.py', content: files[2].content })
    })

    it('creates a Blob with application/zip MIME type', () => {
      const files = [{ name: 'main.py', content: 'print("blob")' }]
      const blob = createZipBlob(files, 'python')
      expect(blob.type).toBe('application/zip')
      expect(blob.size).toBeGreaterThan(0)
    })

    it('uses the static constant filename room-export.zip (never room ID or token)', () => {
      expect(EXPORT_ZIP_FILENAME).toBe('room-export.zip')
      expect(EXPORT_ZIP_FILENAME).not.toContain('token')
      expect(EXPORT_ZIP_FILENAME).not.toMatch(/[0-9a-f]{8}-[0-9a-f]{4}/)
    })

    it('throws error when attempting to build zip with 0 files', () => {
      expect(() => buildZipArchive([])).toThrow('Cannot create zip: no files provided')
    })

    it('refuses to export if any entry filename violates sanitization rules', () => {
      expect(() =>
        buildZipArchive([
          { name: 'valid.py', content: 'ok' },
          { name: '../etc/passwd.py', content: 'evil' },
        ], 'python'),
      ).toThrow('Refusing export with invalid entry name')
    })

    it('guarantees snapshot isolation (subsequent edits do not alter the zip)', () => {
      let code = 'original content'
      const files = [{ name: 'main.py', content: code }]
      const zipBytes = buildZipArchive(files, 'python')

      // Edit string
      code = 'mutated content'
      expect(code).toBe('mutated content')

      // Zip still holds original content
      const entries = readZipEntries(zipBytes)
      expect(entries[0].content).toBe('original content')
    })
  })
})

// client/src/exportZip.ts
// US-57 (FR33 · EPIC 5): Export room files as a standard ZIP archive.
//
// Generates standard PKZIP 2.0 uncompressed (STORE mode, method 0) archives with CRC-32 checksums.
// Zero external dependencies. Works in browser (Blob) and Node.js environments.
import { validateModuleName } from './workspaceApi'

export const EXPORT_ZIP_FILENAME = 'room-export.zip'

export interface ExportZipFileEntry {
  name: string
  content: string
}

/**
 * Precomputed CRC-32 table (IEEE 802.3 standard polynomial 0xEDB88320).
 */
const CRC_TABLE = new Uint32Array(256)
for (let i = 0; i < 256; i++) {
  let c = i
  for (let j = 0; j < 8; j++) {
    c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  }
  CRC_TABLE[i] = c >>> 0
}

/**
 * Computes standard CRC-32 checksum for a byte array.
 */
export function crc32(bytes: Uint8Array): number {
  let c = 0xffffffff
  for (let i = 0; i < bytes.length; i++) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8)
  }
  return (c ^ 0xffffffff) >>> 0
}

/**
 * Sanitizes and validates a zip entry filename:
 * - Refuses/cleans folder paths, directory traversal ('..'), and leading slashes.
 * - Reuses DR4 validateModuleName() rules for language extensions.
 * - Enforces uniqueness across the export archive.
 *
 * A bad name is cleaned or refused, never written as given.
 */
export function sanitizeAndValidateZipEntryName(
  rawName: string,
  language?: string,
  existingNames?: Set<string>,
): { valid: boolean; name?: string; error?: string } {
  if (!rawName || typeof rawName !== 'string') {
    return { valid: false, error: 'Filename is missing or empty' }
  }

  // Refuse directory traversal and leading slashes
  const trimmed = rawName.trim()
  if (trimmed.startsWith('/') || trimmed.startsWith('\\')) {
    return { valid: false, error: `Leading slashes not allowed: '${trimmed}'` }
  }
  if (trimmed.includes('..')) {
    return { valid: false, error: `Directory traversal not allowed: '${trimmed}'` }
  }
  if (trimmed.includes('/') || trimmed.includes('\\')) {
    return { valid: false, error: `Folder parts not allowed in module filename: '${trimmed}'` }
  }

  // Validate against DR4 coursework rules
  const valResult = validateModuleName(trimmed, language)
  if (!valResult.valid) {
    return { valid: false, error: valResult.error }
  }

  // Check uniqueness within the archive
  if (existingNames && existingNames.has(trimmed.toLowerCase())) {
    return { valid: false, error: `Duplicate filename in export: '${trimmed}'` }
  }

  return { valid: true, name: trimmed }
}

/**
 * Builds a standard PKZIP 2.0 uncompressed (STORE) archive byte array.
 * Each entry is snapshotted into memory synchronously.
 */
export function buildZipArchive(
  files: ExportZipFileEntry[],
  language?: string,
): Uint8Array {
  if (!files || files.length === 0) {
    throw new Error('Cannot create zip: no files provided')
  }

  const textEncoder = new TextEncoder()
  const validatedEntries: Array<{ name: string; nameBytes: Uint8Array; contentBytes: Uint8Array; crc: number }> = []
  const existingNames = new Set<string>()

  for (const file of files) {
    const check = sanitizeAndValidateZipEntryName(file.name, language, existingNames)
    if (!check.valid || !check.name) {
      throw new Error(`Refusing export with invalid entry name: ${check.error}`)
    }
    existingNames.add(check.name.toLowerCase())

    const nameBytes = textEncoder.encode(check.name)
    const contentBytes = textEncoder.encode(file.content ?? '')
    const crc = crc32(contentBytes)

    validatedEntries.push({
      name: check.name,
      nameBytes,
      contentBytes,
      crc,
    })
  }

  // Calculate buffer sizes
  let totalLocalHeadersSize = 0
  let totalCentralDirSize = 0

  for (const entry of validatedEntries) {
    totalLocalHeadersSize += 30 + entry.nameBytes.length + entry.contentBytes.length
    totalCentralDirSize += 46 + entry.nameBytes.length
  }

  const endOfCentralDirSize = 22
  const totalZipSize = totalLocalHeadersSize + totalCentralDirSize + endOfCentralDirSize

  const buffer = new ArrayBuffer(totalZipSize)
  const view = new DataView(buffer)
  const bytes = new Uint8Array(buffer)

  let offset = 0
  const localHeaderOffsets: number[] = []

  // 1. Write Local File Headers + File Data
  for (const entry of validatedEntries) {
    localHeaderOffsets.push(offset)

    view.setUint32(offset, 0x04034b50, true) // Local header signature
    view.setUint16(offset + 4, 20, true) // Version needed (2.0)
    view.setUint16(offset + 6, 0x0800, true) // General purpose bit flag (UTF-8 encoding)
    view.setUint16(offset + 8, 0, true) // Compression method: 0 (STORE)
    view.setUint16(offset + 10, 0, true) // Last mod file time
    view.setUint16(offset + 12, 0, true) // Last mod file date
    view.setUint32(offset + 14, entry.crc, true) // CRC-32
    view.setUint32(offset + 18, entry.contentBytes.length, true) // Compressed size
    view.setUint32(offset + 22, entry.contentBytes.length, true) // Uncompressed size
    view.setUint16(offset + 26, entry.nameBytes.length, true) // File name length
    view.setUint16(offset + 28, 0, true) // Extra field length

    offset += 30
    bytes.set(entry.nameBytes, offset)
    offset += entry.nameBytes.length

    bytes.set(entry.contentBytes, offset)
    offset += entry.contentBytes.length
  }

  const centralDirStartOffset = offset

  // 2. Write Central Directory Headers
  for (let i = 0; i < validatedEntries.length; i++) {
    const entry = validatedEntries[i]
    const localOffset = localHeaderOffsets[i]

    view.setUint32(offset, 0x02014b50, true) // Central directory signature
    view.setUint16(offset + 4, 0x0314, true) // Version made by (Unix, 2.0)
    view.setUint16(offset + 6, 20, true) // Version needed
    view.setUint16(offset + 8, 0x0800, true) // Flags (UTF-8)
    view.setUint16(offset + 10, 0, true) // Compression method: 0
    view.setUint16(offset + 12, 0, true) // Time
    view.setUint16(offset + 14, 0, true) // Date
    view.setUint32(offset + 16, entry.crc, true) // CRC-32
    view.setUint32(offset + 20, entry.contentBytes.length, true) // Compressed size
    view.setUint32(offset + 24, entry.contentBytes.length, true) // Uncompressed size
    view.setUint16(offset + 28, entry.nameBytes.length, true) // File name length
    view.setUint16(offset + 30, 0, true) // Extra field length
    view.setUint16(offset + 32, 0, true) // File comment length
    view.setUint16(offset + 34, 0, true) // Disk number start
    view.setUint16(offset + 36, 0, true) // Internal file attributes
    view.setUint32(offset + 38, 0x81a40000, true) // External file attributes (-rw-r--r--)
    view.setUint32(offset + 42, localOffset, true) // Relative offset of local header

    offset += 46
    bytes.set(entry.nameBytes, offset)
    offset += entry.nameBytes.length
  }

  const centralDirSize = offset - centralDirStartOffset

  // 3. Write End of Central Directory Record (EOCD)
  view.setUint32(offset, 0x06054b50, true) // EOCD signature
  view.setUint16(offset + 4, 0, true) // Number of this disk
  view.setUint16(offset + 6, 0, true) // Disk where central directory starts
  view.setUint16(offset + 8, validatedEntries.length, true) // Number of central directory records on this disk
  view.setUint16(offset + 10, validatedEntries.length, true) // Total number of central directory records
  view.setUint32(offset + 12, centralDirSize, true) // Size of central directory
  view.setUint32(offset + 16, centralDirStartOffset, true) // Offset of start of central directory
  view.setUint16(offset + 20, 0, true) // Zip file comment length

  return bytes
}

/**
 * Creates a standard Blob containing the ZIP archive.
 */
export function createZipBlob(files: ExportZipFileEntry[], language?: string): Blob {
  const bytes = buildZipArchive(files, language)
  return new Blob([bytes.buffer as ArrayBuffer], { type: 'application/zip' })
}

/**
 * Triggers a client-side download of a Blob as 'room-export.zip'.
 * Revokes the temporary object URL after triggering.
 */
export function triggerZipDownload(blob: Blob, filename = EXPORT_ZIP_FILENAME): void {
  if (typeof document === 'undefined') return
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.style.display = 'none'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/**
 * Unzips an uncompressed (STORE mode) ZIP archive for testing and verification.
 * Extracts filenames and text contents.
 */
export function readZipEntries(zipBytes: Uint8Array): ExportZipFileEntry[] {
  const view = new DataView(zipBytes.buffer, zipBytes.byteOffset, zipBytes.byteLength)
  const textDecoder = new TextDecoder()
  const entries: ExportZipFileEntry[] = []

  let offset = 0
  while (offset + 30 <= zipBytes.length) {
    const sig = view.getUint32(offset, true)
    if (sig !== 0x04034b50) {
      // Reached central directory or end
      break
    }
    const compression = view.getUint16(offset + 8, true)
    if (compression !== 0) {
      throw new Error(`Unsupported compression method ${compression} in test reader`)
    }
    const compressedSize = view.getUint32(offset + 18, true)
    const nameLength = view.getUint16(offset + 26, true)
    const extraLength = view.getUint16(offset + 28, true)

    const nameBytes = zipBytes.subarray(offset + 30, offset + 30 + nameLength)
    const name = textDecoder.decode(nameBytes)

    const dataOffset = offset + 30 + nameLength + extraLength
    const dataBytes = zipBytes.subarray(dataOffset, dataOffset + compressedSize)
    const content = textDecoder.decode(dataBytes)

    entries.push({ name, content })
    offset = dataOffset + compressedSize
  }

  return entries
}

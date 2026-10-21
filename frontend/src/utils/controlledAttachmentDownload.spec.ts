import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  controlledAttachmentFilename,
  controlledDownloadErrorMessage,
  controlledDownloadProgress,
  controlledDownloadStatusText,
  controlledDownloadTimeoutMs,
  isInlineTextAttachment,
  isGovernedLogAttachment,
  saveControlledBlob,
} from './controlledAttachmentDownload'

afterEach(() => vi.restoreAllMocks())

describe('controlled attachment downloads', () => {
  it('bounds inline preview time while allowing larger archives limited extra time', () => {
    expect(controlledDownloadTimeoutMs()).toBe(30_000)
    expect(controlledDownloadTimeoutMs(10 * 1024 * 1024)).toBe(37_500)
    expect(controlledDownloadTimeoutMs(500 * 1024 * 1024)).toBe(90_000)
  })

  it('only renders small plain text inline and preserves archive extensions', () => {
    expect(isInlineTextAttachment({ mimeType: 'text/plain', fileSizeBytes: 1024 })).toBe(true)
    expect(isInlineTextAttachment({ mimeType: 'text/plain' })).toBe(false)
    expect(isInlineTextAttachment({ mimeType: 'text/plain', fileSizeBytes: Number.NaN })).toBe(false)
    expect(isInlineTextAttachment({ mimeType: 'application/gzip', fileSizeBytes: 1024 })).toBe(false)
    expect(isInlineTextAttachment({ mimeType: 'text/plain', fileSizeBytes: 21 * 1024 * 1024 })).toBe(false)
    expect(controlledAttachmentFilename({ sourceAttachmentId: 5978440, mimeType: 'application/gzip' })).toBe('application-log-5978440.gz')
    expect(controlledAttachmentFilename({ attachmentId: 2, mimeType: 'application/zip' })).toBe('application-log-2.zip')
  })

  it('keeps logs and supported log archives out of the download path', () => {
    expect(isGovernedLogAttachment({ attachmentType: 'LOG', mimeType: 'application/octet-stream' })).toBe(true)
    expect(isGovernedLogAttachment({ sourceAttachmentId: 'nested.tgz', mimeType: 'application/gzip' })).toBe(true)
    expect(isGovernedLogAttachment({ sourceAttachmentId: 'report.pdf', mimeType: 'application/pdf' })).toBe(false)
  })

  it('reports bounded progress and actionable errors', () => {
    expect(controlledDownloadProgress(50, 100)).toBe(50)
    expect(controlledDownloadProgress(100, 100)).toBe(99)
    expect(controlledDownloadProgress(80, undefined, 75)).toBeNull()
    expect(controlledDownloadProgress(Number.NaN, 100)).toBeNull()
    expect(controlledDownloadProgress(50, Number.NaN)).toBeNull()
    expect(controlledDownloadErrorMessage({ code: 'ECONNABORTED' })).toContain('超时')
    expect(controlledDownloadErrorMessage({ code: 'ERR_CANCELED' })).toBeNull()
    expect(controlledDownloadErrorMessage({ response: { status: 403 } })).toContain('无权限')
    expect(controlledDownloadErrorMessage({ response: { status: 404 } })).toContain('文件缺失')
    expect(controlledDownloadErrorMessage({ response: { status: 409 } })).toContain('安全检查')
  })

  it('uses truthful stages for transformed log streams', () => {
    expect(controlledDownloadStatusText('authorizing')).toBe('准备查看')
    expect(controlledDownloadStatusText('streaming', 0)).toBe('服务端解压中')
    expect(controlledDownloadStatusText('streaming', 1.5 * 1024 * 1024)).toContain('1.5 MB')
    expect(controlledDownloadStatusText('parsing', 5 * 1024 * 1024)).toBe('正在生成预览')
  })

  it('attaches the download link and revokes it after a delay', () => {
    vi.useFakeTimers()
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
    const create = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test')
    const revoke = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    saveControlledBlob(new Blob(['log']), 'sample.gz')
    const anchor = document.querySelector('a[download="sample.gz"]')
    expect(anchor).not.toBeNull()
    expect(click).toHaveBeenCalledOnce()
    expect(create).toHaveBeenCalledOnce()
    expect(revoke).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1_000)
    expect(document.querySelector('a[download="sample.gz"]')).toBeNull()
    expect(revoke).toHaveBeenCalledWith('blob:test')
    vi.useRealTimers()
  })
})

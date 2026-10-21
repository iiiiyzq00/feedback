import { describe, expect, it } from 'vitest'
import { boundedThumbnailSize, governedBlobMatches, governedMediaKind, videoThumbnailSeekTime } from './governedMedia'

describe('governedMedia', () => {
  it('allows only the frozen browser media contract', () => {
    expect(governedMediaKind({ id: 1, attachmentType: 'SCREENSHOT', mimeType: 'image/jpeg' })).toBe('IMAGE')
    expect(governedMediaKind({ id: 2, attachmentType: 'VIDEO', mimeType: 'video/webm' })).toBe('VIDEO')
    expect(governedMediaKind({ id: 3, attachmentType: 'SCREENSHOT', mimeType: 'image/svg+xml' })).toBeUndefined()
    expect(governedMediaKind({ id: 4, attachmentType: 'VIDEO', mimeType: 'video/quicktime' })).toBe('VIDEO')
  })

  it('rejects a streamed MIME that disagrees with metadata', () => {
    const item = { id: 1, attachmentType: 'IMAGE', mimeType: 'image/png' }
    expect(governedBlobMatches(item, new Blob(['x'], { type: 'image/png' }))).toBe(true)
    expect(governedBlobMatches(item, new Blob(['x'], { type: 'text/html' }))).toBe(false)
  })

  it('bounds both edge length and pixel count', () => {
    const size = boundedThumbnailSize(4000, 2000)!
    expect(Math.max(size.width, size.height)).toBeLessThanOrEqual(240)
    expect(size.width * size.height).toBeLessThanOrEqual(57_600)
  })

  it('selects an early representative video frame instead of the black opening frame', () => {
    expect(videoThumbnailSeekTime(Number.NaN)).toBe(0)
    expect(videoThumbnailSeekTime(0.4)).toBeCloseTo(0.2)
    expect(videoThumbnailSeekTime(2)).toBe(1)
    expect(videoThumbnailSeekTime(30)).toBe(3)
    expect(videoThumbnailSeekTime(120)).toBe(5)
  })
})

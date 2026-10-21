import { afterEach, describe, expect, it, vi } from 'vitest'
import { dismissAuthBootAfterPaint, hideAuthBootImmediately } from './authTransition'

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

function boot() {
  document.body.innerHTML = '<div id="feishu-auth-boot"></div>'
  return document.getElementById('feishu-auth-boot') as HTMLElement
}

describe('auth transition boot frame', () => {
  it('stays visible for two paints and then fades before being hidden', () => {
    vi.useFakeTimers()
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      frames.push(callback)
      return frames.length
    })
    const element = boot()

    dismissAuthBootAfterPaint()
    expect(element.hidden).toBe(false)
    expect(frames).toHaveLength(1)

    frames.shift()?.(0)
    expect(element.classList.contains('is-leaving')).toBe(false)
    frames.shift()?.(16)
    expect(element.classList.contains('is-leaving')).toBe(true)
    expect(element.getAttribute('aria-hidden')).toBe('true')

    vi.advanceTimersByTime(180)
    expect(element.hidden).toBe(true)
    expect(element.classList.contains('is-leaving')).toBe(false)
  })

  it('can be removed immediately when login cannot complete', () => {
    const element = boot()
    element.classList.add('is-leaving')

    hideAuthBootImmediately()

    expect(element.hidden).toBe(true)
    expect(element.classList.contains('is-leaving')).toBe(false)
  })
})

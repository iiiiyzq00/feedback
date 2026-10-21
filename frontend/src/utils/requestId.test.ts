import{describe,expect,it,vi}from'vitest'
import{createRequestId}from'./requestId'

describe('createRequestId',()=>{
  it('uses the native UUID implementation when available',()=>{
    const native='11111111-1111-4111-8111-111111111111'
    vi.stubGlobal('crypto',{randomUUID:()=>native})
    expect(createRequestId()).toBe(native)
    vi.unstubAllGlobals()
  })

  it('creates an RFC 4122 version 4 id when randomUUID is unavailable',()=>{
    vi.stubGlobal('crypto',{getRandomValues:(bytes:Uint8Array)=>{bytes.fill(1);return bytes}})
    expect(createRequestId()).toMatch(/^01010101-0101-4101-8101-010101010101$/)
    vi.unstubAllGlobals()
  })
})

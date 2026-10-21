const fs = require('fs')
const crypto = require('crypto')
const http = require('http')

const host = process.env.SDKV_HOST || '127.0.0.1'
const port = Number(process.env.SDKV_PORT || 18080)
const appKey = required('SDKV_APP_KEY')
const appSecret = required('SDKV_APP_SECRET')
const packageName = required('SDKV_PACKAGE_NAME')
const functionModule = process.env.SDKV_FUNCTION_MODULE || 'SDK_LIVE_VALIDATION'
const screenshotFile = process.env.SDKV_SCREENSHOT_FILE
const videoFile = process.env.SDKV_VIDEO_FILE
const includeLog = process.env.SDKV_INCLUDE_LOG !== 'false'

function required(name) {
  const value = process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex')
}

function request(method, path, body = Buffer.alloc(0), headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ hostname: host, port, method, path, headers: {
      'Content-Length': body.length,
      ...headers,
    } }, response => {
      const chunks = []
      response.on('data', chunk => chunks.push(chunk))
      response.on('end', () => resolve({
        status: response.statusCode,
        body: Buffer.concat(chunks),
      }))
    })
    req.on('error', reject)
    req.end(body)
  })
}

function sdkHeaders(method, path, digest) {
  const timestamp = Date.now().toString()
  const nonce = crypto.randomUUID()
  const signature = crypto.createHmac('sha256', appSecret)
    .update(method + path + timestamp + nonce + digest)
    .digest('hex')
  return {
    'X-App-Key': appKey,
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': signature,
  }
}

function encryptAesGcm(plain, key) {
  const nonce = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', key, nonce)
  const ciphertext = Buffer.concat([cipher.update(plain), cipher.final()])
  return { nonce, ciphertext, tag: cipher.getAuthTag() }
}

function wrapKey(publicKey, key) {
  return crypto.publicEncrypt({
    key: publicKey,
    oaepHash: 'sha256',
    padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
  }, key)
}

function encryptAttachment(input, publicKey) {
  const key = crypto.randomBytes(32)
  const encrypted = encryptAesGcm(input.bytes, key)
  const envelope = Buffer.concat([
    Buffer.from('FBA2', 'ascii'), encrypted.nonce, encrypted.ciphertext, encrypted.tag,
  ])
  return {
    ...input,
    id: crypto.randomUUID(),
    encryptedDataKey: wrapKey(publicKey, key).toString('base64'),
    envelope,
    sha256: sha256(envelope),
  }
}

function multipart(fields, fileName, fileBytes) {
  const boundary = `----sdk-validation-${crypto.randomBytes(12).toString('hex')}`
  const parts = []
  for (const [name, value] of Object.entries(fields)) {
    parts.push(Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n${value}\r\n`,
    ))
  }
  parts.push(Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="encrypted_chunk"; filename="${fileName}"\r\nContent-Type: application/octet-stream\r\n\r\n`,
  ))
  parts.push(fileBytes)
  parts.push(Buffer.from(`\r\n--${boundary}--\r\n`))
  return { body: Buffer.concat(parts), contentType: `multipart/form-data; boundary=${boundary}` }
}

async function uploadAttachment(receiptId, attachment) {
  const metadata = JSON.stringify({
    client_attachment_id: attachment.id,
    type: attachment.type,
    mime: attachment.mime,
    size_bytes: attachment.envelope.length,
    sha256: attachment.sha256,
    encrypted_data_key: attachment.encryptedDataKey,
    algorithm_version: 'FBA2-AES-256-GCM+RSA-OAEP-256',
  })
  const chunkSize = 1024 * 1024
  const totalChunks = Math.max(1, Math.ceil(attachment.envelope.length / chunkSize))
  let finalAck
  for (let index = 0; index < totalChunks; index += 1) {
    const chunk = attachment.envelope.subarray(index * chunkSize, (index + 1) * chunkSize)
    const chunkHash = sha256(chunk)
    const path = `/api/v1/sdk/ingest/${receiptId}/attachments/chunks`
    const digest = sha256(Buffer.concat([
      Buffer.from(metadata), Buffer.from(index.toString()), Buffer.from(totalChunks.toString()), chunk,
    ]))
    const form = multipart({
      metadata,
      chunk_index: index,
      total_chunks: totalChunks,
      chunk_sha256: chunkHash,
    }, `${attachment.id}.enc.${index}`, chunk)
    const response = await request('POST', path, form.body, {
      ...sdkHeaders('POST', path, digest),
      'Content-Type': form.contentType,
    })
    if (response.status !== 200) {
      const detail = response.body.toString('utf8').slice(0, 500)
      throw new Error(`attachment ${attachment.type} chunk ${index} failed: HTTP ${response.status} ${detail}`)
    }
    finalAck = JSON.parse(response.body.toString('utf8')).data
  }
  return { id: attachment.id, type: attachment.type, mime: attachment.mime, ...finalAck }
}

async function main() {
  const configResponse = await request(
    'GET', `/api/v1/sdk/config?package_name=${encodeURIComponent(packageName)}`,
  )
  if (configResponse.status !== 200) throw new Error(`config failed: HTTP ${configResponse.status}`)
  const remoteConfig = JSON.parse(configResponse.body.toString('utf8')).data
  const transportKeyId = remoteConfig.transport_key_id
  const publicKey = crypto.createPublicKey(remoteConfig.transport_public_key_pem)

  const inputs = []
  if (screenshotFile) inputs.push({
    type: 'SCREENSHOT', mime: 'image/png', bytes: fs.readFileSync(screenshotFile),
  })
  if (videoFile) inputs.push({
    type: 'SCREEN_RECORDING', mime: 'video/mp4', bytes: fs.readFileSync(videoFile),
  })
  if (includeLog) inputs.push({
    type: 'LOG', mime: 'text/plain',
    bytes: Buffer.from(`SDK live validation log\napp=${appKey}\nmodule=${functionModule}\n`, 'utf8'),
  })
  const attachments = inputs.map(input => encryptAttachment(input, publicKey))

  const dataKey = crypto.randomBytes(32)
  const encrypted = encryptAesGcm(Buffer.from(JSON.stringify({
    description: `run8 SDK真实链路验证：${appKey}`,
    input_methods: ['TEXT'],
    business_context: { validation: 'run8-live', attachment_types: inputs.map(it => it.type).join(',') },
  })), dataKey)
  const clientFeedbackId = `SDKV-${Date.now().toString(36)}-${crypto.randomBytes(4).toString('hex')}`
  const body = Buffer.from(JSON.stringify({
    client_feedback_id: clientFeedbackId,
    package_name: packageName,
    app_version: '1.0.0-run8-validation',
    function_module: functionModule,
    issue_type: '功能异常',
    source_event_time: new Date().toISOString(),
    os_version: 'Android 16',
    network_type: 'WIFI',
    trigger_type: 'SDK_TEST',
    encrypted_payload: {
      key_id: transportKeyId,
      algorithm: 'AES-256-GCM+RSA-OAEP-256',
      encrypted_data_key: wrapKey(publicKey, dataKey).toString('base64'),
      nonce: encrypted.nonce.toString('base64'),
      ciphertext: encrypted.ciphertext.toString('base64'),
      tag: encrypted.tag.toString('base64'),
    },
    encrypted_contact_payload: null,
    attachment_manifest: attachments.map(attachment => ({
      client_attachment_id: attachment.id,
      type: attachment.type,
      size_bytes: attachment.envelope.length,
      sha256: attachment.sha256,
    })),
    schema_version: 'sdk-form-v1',
  }))
  const feedbackPath = '/api/v1/sdk/feedback'
  const feedbackResponse = await request('POST', feedbackPath, body, {
    ...sdkHeaders('POST', feedbackPath, sha256(body)),
    'Content-Type': 'application/json',
  })
  if (feedbackResponse.status !== 200) {
    throw new Error(`feedback failed: HTTP ${feedbackResponse.status}`)
  }
  const ack = JSON.parse(feedbackResponse.body.toString('utf8')).data
  const uploaded = []
  for (const attachment of attachments) uploaded.push(await uploadAttachment(ack.receipt_id, attachment))

  let progress
  const statusPath = `/api/v1/sdk/feedback/${ack.receipt_id}/status`
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const response = await request('GET', statusPath, Buffer.alloc(0), {
      ...sdkHeaders('GET', statusPath, sha256(Buffer.alloc(0))),
    })
    progress = JSON.parse(response.body.toString('utf8')).data
    if (progress.status !== 'PROCESSING') break
    await new Promise(resolve => setTimeout(resolve, 500))
  }
  console.log(JSON.stringify({
    appKey,
    clientFeedbackId,
    receiptId: ack.receipt_id,
    ackStatus: ack.status,
    progressStatus: progress?.status,
    progressStage: progress?.stage,
    attachments: uploaded,
  }))
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})

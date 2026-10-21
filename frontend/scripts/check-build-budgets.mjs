import { readFileSync, statSync, writeFileSync } from 'node:fs'
import { gzipSync } from 'node:zlib'

const manifest = JSON.parse(readFileSync('dist/.vite/manifest.json', 'utf8'))
const budgets = JSON.parse(readFileSync('build-budgets.json', 'utf8'))
const gzipBytes = (file) => gzipSync(readFileSync(`dist/${file}`), { level: 9 }).length
const assets = [...new Set(Object.values(manifest).flatMap(item => [item.file, ...(item.css ?? [])]).filter(Boolean))]
const js = assets.filter(file => file.endsWith('.js')).map(file => ({ file, rawBytes: statSync(`dist/${file}`).size, gzipBytes: gzipBytes(file) }))
const css = assets.filter(file => file.endsWith('.css')).map(file => ({ file, rawBytes: statSync(`dist/${file}`).size, gzipBytes: gzipBytes(file) }))

const entryKey = Object.keys(manifest).find(key => manifest[key].isEntry)
if (!entryKey) throw new Error('Vite manifest does not contain an entry chunk')
const initialKeys = new Set()
function visit(key) {
  if (initialKeys.has(key) || !manifest[key]) return
  initialKeys.add(key)
  for (const dependency of manifest[key].imports ?? []) visit(dependency)
}
visit(entryKey)
const initialFiles = [...initialKeys].map(key => manifest[key].file).filter(file => file?.endsWith('.js'))
const initialJsGzipBytes = [...new Set(initialFiles)].reduce((total, file) => total + gzipBytes(file), 0)
const largestJs = js.sort((a, b) => b.gzipBytes - a.gzipBytes)[0]
const largestCss = css.sort((a, b) => b.gzipBytes - a.gzipBytes)[0]
const report = { initialJsGzipBytes, initialFiles: [...new Set(initialFiles)].sort(), largestJs, largestCss, budgets }
writeFileSync('dist/bundle-report.json', `${JSON.stringify(report, null, 2)}\n`)

const failures = []
if (initialJsGzipBytes > budgets.initialJsGzipBytes) failures.push(`initial JS gzip ${initialJsGzipBytes} > ${budgets.initialJsGzipBytes}`)
if (largestJs.gzipBytes > budgets.maxJsGzipBytes) failures.push(`largest JS gzip ${largestJs.gzipBytes} > ${budgets.maxJsGzipBytes} (${largestJs.file})`)
if (largestCss.gzipBytes > budgets.maxCssGzipBytes) failures.push(`largest CSS gzip ${largestCss.gzipBytes} > ${budgets.maxCssGzipBytes} (${largestCss.file})`)
console.log(`BUNDLE_BUDGET=${failures.length ? 'FAIL' : 'PASS'} initial_js_gzip=${initialJsGzipBytes}/${budgets.initialJsGzipBytes} max_js_gzip=${largestJs.gzipBytes}/${budgets.maxJsGzipBytes} max_css_gzip=${largestCss.gzipBytes}/${budgets.maxCssGzipBytes}`)
if (failures.length) {
  for (const failure of failures) console.error(`BUDGET_EXCEEDED ${failure}`)
  process.exit(1)
}

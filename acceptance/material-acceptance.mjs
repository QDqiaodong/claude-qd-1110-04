#!/usr/bin/env node
/**
 * 画材消耗看板 · 一键验收脚本
 *
 * 用法：
 *   sh start.sh                        # 先把栈起起来
 *   node acceptance/material-acceptance.mjs
 *   BASE=http://127.0.0.1:8420 node acceptance/material-acceptance.mjs
 *
 * 覆盖链路：
 *   A 月末边界：结束日期当天整条计入，跨月查询不漏月末记录，前一天则不含
 *   B 空范围：无记录范围返回空汇总 / 空明细；开始日期晚于结束日期被可读拦截
 *   C 材料 × 学员交叉筛选：只命中指定组合，汇总数字与明细记录同口径一致
 *   D 归还后的净消耗：领用扣库存 → 归还回加库存 → 净消耗 = 领用 - 归还，当前余量与库存页同源
 *   E 删除学员的历史追溯：仅有领用历史的学员可删，删除后汇总 / 明细仍显示原姓名快照；
 *     学员改名后历史记录仍显示领用时姓名；有排课的学员删除仍被拦截（回归）
 *   F 连续切换筛选的旧请求覆盖：并发不同筛选的汇总请求各自隔离、互不串扰
 *     （服务端无状态是前端「序号丢弃旧响应」的前提；前端刷新 / 返回再进入会整体重查）
 */

const BASE = process.env.BASE || 'http://127.0.0.1:8420'
const TS = Date.now()

let pass = 0
let fail = 0
const failures = []

function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`  \x1b[32m✓\x1b[0m ${name}`) }
  else { fail++; failures.push(name); console.log(`  \x1b[31m✗ ${name}${extra ? ' — ' + extra : ''}\x1b[0m`) }
}
function section(t) { console.log(`\n=== ${t} ===`) }

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined
  })
  let json = null
  try { json = await res.json() } catch { /* 非 JSON */ }
  return { status: res.status, json }
}

const qs = (o) => {
  const p = Object.entries(o).filter(([, v]) => v !== undefined && v !== null && v !== '')
  return p.length ? '?' + new URLSearchParams(p).toString() : ''
}
const summary = (f = {}) => api('GET', '/api/material-uses/summary' + qs(f))
const useList = (f = {}) => api('GET', '/api/material-uses' + qs(f))

async function mustFail(name, p, msgPart) {
  const r = await p
  const good = r.json && r.json.ok === false && r.json.message && r.json.message.includes(msgPart)
  ok(name, good, `got ${JSON.stringify(r.json)}`)
  return r
}

/** 在汇总里找「画材 × 学员」行 */
const findRow = (rows, materialId, studentId) =>
  (rows || []).find((r) => r.materialId === materialId && r.studentId === studentId)

/** 明细按类型合计，用于和汇总行对账（同一套口径） */
function sumByKind(details) {
  let checkout = 0
  let returned = 0
  for (const d of details || []) {
    if (d.kind === '归还') returned += d.qty
    else checkout += d.qty
  }
  return { checkout, returned, net: checkout - returned }
}

async function main() {
  console.log(`目标后端：${BASE}`)

  // ---------------------------------------------------------------- 0. 连通 & 准备
  section('0. 连通性与数据准备（专用画材 ×3 学员，与种子数据隔离）')
  const ping = await api('GET', '/api/students')
  ok('后端可访问且返回 {ok:true}', ping.json?.ok === true, JSON.stringify(ping.json))
  if (!ping.json?.ok) process.exit(1)

  let r = await api('POST', '/api/art-materials', { name: `验收颜料-${TS}`, category: '颜料', stock: 100, unit: '支', status: '在库' })
  ok('新建画材 M1 成功', r.json?.ok === true)
  const M1 = r.json.data
  r = await api('POST', '/api/art-materials', { name: `验收画笔-${TS}`, category: '画笔', stock: 50, unit: '支', status: '在库' })
  ok('新建画材 M2 成功', r.json?.ok === true)
  const M2 = r.json.data

  const mk = async (tag) => {
    const res = await api('POST', '/api/students', { code: `M${TS}${tag}`.slice(0, 16), name: `验收学员${tag}`, level: '成人', status: '在读' })
    return res.json.data
  }
  const A = await mk('甲')
  const B = await mk('乙')
  const C = await mk('丙')
  ok('新建学员 A/B/C 成功', !!A?.id && !!B?.id && !!C?.id)

  const checkout = (s, m, qty, useDate) => api('POST', '/api/material-uses', { studentId: s, materialId: m, qty, useDate })
  const giveBack = (s, m, qty, useDate) => api('POST', '/api/material-uses/returns', { studentId: s, materialId: m, qty, useDate })
  const stockOf = async (id) => (await api('GET', '/api/art-materials')).json.data.find((m) => m.id === id)?.stock

  // ---------------------------------------------------------------- 1. 月末边界
  section('1. 月末边界：结束日期当天计入，跨月不漏月末')
  await checkout(A.id, M1.id, 4, '2026-01-31') // 一月月末
  await checkout(A.id, M1.id, 3, '2026-02-01') // 二月月初
  await checkout(B.id, M1.id, 2, '2026-01-15')

  r = await summary({ from: '2026-01-01', to: '2026-01-31', materialId: M1.id })
  ok('1 月范围含月末当天记录（A=4、B=2）',
    findRow(r.json.data, M1.id, A.id)?.checkoutQty === 4 && findRow(r.json.data, M1.id, B.id)?.checkoutQty === 2,
    JSON.stringify(r.json.data))
  ok('1 月范围不含 2 月 1 日的记录（仅 A、B 两行）', r.json.data.length === 2)

  r = await summary({ from: '2026-01-01', to: '2026-01-30', materialId: M1.id, studentId: A.id })
  ok('结束日期提前一天则月末记录不在范围内（空汇总）', Array.isArray(r.json.data) && r.json.data.length === 0)

  r = await summary({ from: '2026-01-01', to: '2026-02-28', materialId: M1.id, studentId: A.id })
  ok('跨月查询同时含月末与月初（A 净消耗 4+3=7）',
    findRow(r.json.data, M1.id, A.id)?.checkoutQty === 7 && findRow(r.json.data, M1.id, A.id)?.netQty === 7,
    JSON.stringify(r.json.data))

  r = await useList({ from: '2026-01-01', to: '2026-02-28', materialId: M1.id, studentId: A.id })
  const janFeb = sumByKind(r.json.data)
  ok('同参明细与汇总同口径（明细领用合计也是 7）', janFeb.checkout === 7 && r.json.data.length === 2)
  ok('明细按业务日期升序（月末在前、月初在后）',
    r.json.data[0]?.useDate === '2026-01-31' && r.json.data[1]?.useDate === '2026-02-01')

  // ---------------------------------------------------------------- 2. 空范围与非法范围
  section('2. 空范围与非法范围')
  r = await summary({ from: '2030-01-01', to: '2030-01-31' })
  ok('无记录的范围返回空汇总（不是报错）', r.json?.ok === true && Array.isArray(r.json.data) && r.json.data.length === 0)
  r = await useList({ from: '2030-01-01', to: '2030-01-31' })
  ok('无记录的范围返回空明细', r.json?.ok === true && r.json.data.length === 0)
  await mustFail('开始日期晚于结束日期被可读拦截',
    summary({ from: '2026-02-01', to: '2026-01-01' }), '开始日期不能晚于结束日期')
  await mustFail('非法日期格式被可读拦截（前端据此保留筛选、清空过期汇总）',
    summary({ from: '2026/01/01' }), 'YYYY-MM-DD')

  // ---------------------------------------------------------------- 3. 材料 × 学员交叉筛选
  section('3. 材料 × 学员交叉筛选')
  await checkout(A.id, M2.id, 6, '2026-02-10')
  await checkout(B.id, M2.id, 1, '2026-02-10')

  r = await summary({ materialId: M1.id, studentId: A.id })
  ok('M1×A 只命中 M1 上 A 的台账（领用 7）',
    r.json.data.length === 1 && findRow(r.json.data, M1.id, A.id)?.checkoutQty === 7)
  r = await summary({ materialId: M2.id, studentId: A.id })
  ok('M2×A 命中 M2 上 A 的台账（领用 6）', findRow(r.json.data, M2.id, A.id)?.checkoutQty === 6)
  r = await summary({ materialId: M1.id, studentId: C.id })
  ok('M1×C 无台账 → 空汇总（交叉条件互不串行）', r.json.data.length === 0)

  r = await summary({ materialId: M2.id })
  const detailsM2 = (await useList({ materialId: M2.id })).json.data
  const sumM2 = sumByKind(detailsM2)
  const rowSum = r.json.data.reduce((acc, x) => ({ checkout: acc.checkout + x.checkoutQty, returned: acc.returned + x.returnQty }), { checkout: 0, returned: 0 })
  ok('M2 全部汇总行合计 == 同参明细合计（同一套状态口径）',
    rowSum.checkout === sumM2.checkout && rowSum.returned === sumM2.returned,
    `summary=${JSON.stringify(rowSum)} details=${JSON.stringify(sumM2)}`)

  // ---------------------------------------------------------------- 4. 归还后的净消耗与当前余量
  section('4. 归还后的净消耗变化，当前余量与库存页同源')
  const m1Before = await stockOf(M1.id)
  ok('M1 期初库存 = 100 - 已领 9 = 91', m1Before === 91, `stock=${m1Before}`)

  await checkout(A.id, M1.id, 5, '2026-02-15')
  r = await summary({ materialId: M1.id, studentId: A.id })
  let row = findRow(r.json.data, M1.id, A.id)
  ok('再领 5 后：领用 12 / 归还 0 / 净消耗 12',
    row.checkoutQty === 12 && row.returnQty === 0 && row.netQty === 12, JSON.stringify(row))
  ok('汇总行当前余量与库存页同一数字（86）',
    row.currentStock === 86 && (await stockOf(M1.id)) === 86, `row=${row.currentStock} stock=${await stockOf(M1.id)}`)

  r = await giveBack(A.id, M1.id, 2, '2026-02-20')
  ok('归还登记成功（回加库存）', r.json?.ok === true && r.json.data?.kind === '归还' && r.json.data?.studentName === '验收学员甲')
  r = await summary({ materialId: M1.id, studentId: A.id })
  row = findRow(r.json.data, M1.id, A.id)
  ok('归还 2 后：领用 12 / 归还 2 / 净消耗 10',
    row.checkoutQty === 12 && row.returnQty === 2 && row.netQty === 10, JSON.stringify(row))
  ok('归还后当前余量回加到 88（与库存页一致）',
    row.currentStock === 88 && (await stockOf(M1.id)) === 88)

  r = await useList({ materialId: M1.id, studentId: A.id })
  const afterReturn = sumByKind(r.json.data)
  ok('归还记录出现在同口径明细里（领 12 / 还 2）', afterReturn.checkout === 12 && afterReturn.returned === 2)

  await mustFail('归还数量为 0 被拦截', giveBack(A.id, M1.id, 0, '2026-02-20'), '数量须大于 0')
  await mustFail('不存在学员的归还被拦截', giveBack(999999, M1.id, 1, '2026-02-20'), '学员不存在')

  // 停用画材允许归还（只是回收入库），但仍不可领用
  await api('PUT', `/api/art-materials/${M2.id}`, { status: '停用' })
  r = await giveBack(B.id, M2.id, 1, '2026-02-21')
  ok('停用画材仍可归还入库', r.json?.ok === true && (await stockOf(M2.id)) === 44, `stock=${await stockOf(M2.id)}`)
  await mustFail('停用画材仍不可领用', checkout(B.id, M2.id, 1, '2026-02-21'), '已停用')
  await api('PUT', `/api/art-materials/${M2.id}`, { status: '在库' })

  // ---------------------------------------------------------------- 5. 删除学员后的历史追溯
  section('5. 删除学员：历史领用仍显示原姓名快照')
  await checkout(C.id, M2.id, 3, '2026-02-05')
  r = await api('DELETE', `/api/students/${C.id}`)
  ok('仅有领用历史的学员可以删除', r.json?.ok === true, JSON.stringify(r.json))
  r = await api('GET', '/api/students')
  ok('删除后学员列表已无丙', !r.json.data.some((s) => s.id === C.id))

  r = await useList({ studentId: C.id })
  ok('已删除学员的明细记录仍在，且显示原姓名快照',
    r.json.data.length === 1 && r.json.data[0].studentName === '验收学员丙' && r.json.data[0].studentDeleted === true,
    JSON.stringify(r.json.data))
  r = await summary({ materialId: M2.id })
  const cRow = findRow(r.json.data, M2.id, C.id)
  ok('汇总行保留已删除学员的原姓名与「已删除」标记',
    cRow?.studentName === '验收学员丙' && cRow?.studentDeleted === true && cRow?.checkoutQty === 3, JSON.stringify(cRow))

  r = await api('PUT', `/api/students/${A.id}`, { name: '验收学员甲改' })
  ok('学员改名成功', r.json?.data?.name === '验收学员甲改')
  r = await useList({ materialId: M1.id, studentId: A.id })
  ok('改名后历史记录仍显示领用时的原姓名（快照）',
    r.json.data.every((d) => d.studentName === '验收学员甲') && r.json.data[0].studentDeleted === false)

  // 回归：有排课的学员删除仍被拦截
  const D = await mk('丁')
  await api('POST', '/api/course-schedules', { studentId: D.id, courseName: '验收回归课', date: '2026-12-01', startMin: 540, endMin: 600 })
  r = await api('DELETE', `/api/students/${D.id}`)
  ok('有排课的学员删除仍被拦截并给出可读原因', r.json?.ok === false && /历史业务数据/.test(r.json.message))

  // ---------------------------------------------------------------- 6. 连续切换筛选的旧请求隔离
  section('6. 并发不同筛选互不串扰（旧请求覆盖的服务端前提）')
  const [x, y, z] = await Promise.all([
    summary({ materialId: M1.id }),
    summary({ materialId: M2.id }),
    summary({ materialId: M1.id, studentId: B.id })
  ])
  ok('并发请求 M1 的汇总只含 M1 行', x.json.data.every((row2) => row2.materialId === M1.id) && x.json.data.length > 0)
  ok('并发请求 M2 的汇总只含 M2 行', y.json.data.every((row2) => row2.materialId === M2.id) && y.json.data.length > 0)
  ok('并发请求 M1×B 的汇总只含 B 的 M1 行（领用 2）',
    z.json.data.length === 1 && findRow(z.json.data, M1.id, B.id)?.checkoutQty === 2)
  const [l1, l2] = await Promise.all([
    useList({ from: '2026-01-01', to: '2026-01-31', materialId: M1.id }),
    useList({ from: '2026-02-01', to: '2026-02-28', materialId: M1.id })
  ])
  ok('并发不同日期范围的明细各自隔离（1 月 2 条 / 2 月 3 条）',
    l1.json.data.length === 2 && l1.json.data.every((d) => d.useDate <= '2026-01-31')
      && l2.json.data.length === 3 && l2.json.data.every((d) => d.useDate >= '2026-02-01'),
    `jan=${l1.json.data.length} feb=${l2.json.data.length}`)

  // ---------------------------------------------------------------- 7. 全量口径总对账
  section('7. 汇总 ↔ 明细 ↔ 库存三方对账（同一套状态口径）')
  const allSummary = (await summary()).json.data
  const allDetails = (await useList()).json.data
  const byPair = {}
  for (const d of allDetails) {
    const k = `${d.materialId}:${d.studentId}`
    byPair[k] = byPair[k] || { checkout: 0, returned: 0 }
    byPair[k][d.kind === '归还' ? 'returned' : 'checkout'] += d.qty
  }
  const mismatch = allSummary.filter((row2) => {
    const d = byPair[`${row2.materialId}:${row2.studentId}`] || { checkout: 0, returned: 0 }
    return row2.checkoutQty !== d.checkout || row2.returnQty !== d.returned
      || row2.netQty !== row2.checkoutQty - row2.returnQty
  })
  ok('每个汇总行都与同口径明细合计一致，且净消耗 = 领用 - 归还', mismatch.length === 0,
    mismatch.map((m) => `${m.materialId}:${m.studentId}`).join(','))
  ok('汇总行数 == 有台账的「画材 × 学员」组合数', allSummary.length === Object.keys(byPair).length,
    `summary=${allSummary.length} pairs=${Object.keys(byPair).length}`)
  const mats = (await api('GET', '/api/art-materials')).json.data
  const stockMap = Object.fromEntries(mats.map((m) => [m.id, m.stock]))
  ok('所有汇总行当前余量与库存页实时库存一致',
    allSummary.every((row2) => row2.currentStock === stockMap[row2.materialId]))

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${'='.repeat(48)}`)
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); process.exit(1) }
  console.log('\x1b[32m全部验收通过 ✅\x1b[0m')
}

main().catch((e) => { console.error('验收脚本异常：', e); process.exit(2) })

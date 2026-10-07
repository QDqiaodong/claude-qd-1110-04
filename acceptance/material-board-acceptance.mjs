#!/usr/bin/env node
/**
 * 画材消耗看板 · 一键验收脚本
 *
 * 用法：
 *   sh start.sh
 *   node acceptance/material-board-acceptance.mjs
 *
 * 覆盖：空范围、跨月月末边界、材料与学员交叉筛选、归还后的净消耗和当前余量、
 *      删除学员后的原姓名快照、连续切换筛选时旧响应不能覆盖新结果的接口可判定条件。
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

const qs = (o = {}) => {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(o)) {
    if (v !== null && v !== undefined && v !== '') p.set(k, v)
  }
  const s = p.toString()
  return s ? `?${s}` : ''
}
const dateISO = (d) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const firstOfNextMonth = (d) => new Date(d.getFullYear(), d.getMonth() + 1, 1)
const lastOfMonth = (d) => new Date(d.getFullYear(), d.getMonth() + 1, 0)
const plusDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  console.log(`目标后端：${BASE}`)
  const ping = await api('GET', '/api/students')
  ok('后端可访问且返回 {ok:true}', ping.json?.ok === true, JSON.stringify(ping.json))
  if (!ping.json?.ok) process.exit(1)

  // ---------------------------------------------------------------- 0. 准备数据
  section('0. 准备：学员、材料、跨月领用与归还')
  const now = new Date()
  const monthEnd = lastOfMonth(now)
  const nextStart = firstOfNextMonth(now)
  const d1 = dateISO(monthEnd)
  const d2 = dateISO(nextStart)
  const d3 = dateISO(plusDays(nextStart, 1))

  const createStudent = async (suffix) => {
    const code = `B${TS}${suffix}`.slice(0, 16)
    const r = await api('POST', '/api/students', {
      code, name: `看板验收${suffix}`, phone: `138${String(TS + suffix).slice(-8)}`, level: '成人', status: '在读'
    })
    if (!r.json?.ok) throw new Error(r.json.message)
    return r.json.data
  }
  const createMaterial = async (suffix, stock) => {
    const r = await api('POST', '/api/art-materials', {
      name: `看板验收材料${TS}-${suffix}`, category: '工具', stock, unit: '件', status: '在库'
    })
    if (!r.json?.ok) throw new Error(r.json.message)
    return r.json.data
  }

  const stuA = await createStudent('A')
  const stuB = await createStudent('B')
  const stuC = await createStudent('C')
  const matX = await createMaterial('X', 100)
  const matY = await createMaterial('Y', 80)
  const matR = await createMaterial('R', 20)
  const matD = await createMaterial('D', 10)
  ok('准备 3 名学员与 4 个材料', [stuA, stuB, stuC, matX, matY, matR, matD].every((x) => x?.id))

  const issue = async (student, material, qty, date) => {
    const r = await api('POST', '/api/material-uses', {
      studentId: student.id, materialId: material.id, qty, useDate: date
    })
    ok(`新建领用：${material.name.slice(-1)} / ${student.name} / ${qty} / ${date}`,
      r.json?.ok && r.json.data?.recordType === '领用' && r.json.data.studentName === student.name,
      JSON.stringify(r.json))
    return r.json.data
  }

  const rows = [
    await issue(stuA, matX, 2, d1),
    await issue(stuB, matX, 3, d2),
    await issue(stuA, matY, 4, d2),
    await issue(stuB, matY, 1, d3)
  ]
  const issueA_X = rows[0]
  const issueB_X = rows[1]
  const issueA_Y = rows[2]
  const issueB_Y = rows[3]

  // ---------------------------------------------------------------- 1. 空范围
  section('1. 空范围：开始日期晚于结束日期时返回空汇总和空明细')
  let r = await api('GET', `/api/material-uses/board${qs({ startDate: d2, endDate: d1 })}`)
  ok('空范围 board 返回成功但 summaries/records 均为空',
    r.json?.ok && r.json.data.emptyRange === true
    && r.json.data.summaries.length === 0 && r.json.data.records.length === 0,
    JSON.stringify(r.json))
  r = await api('GET', `/api/material-uses${qs({ startDate: d2, endDate: d1 })}`)
  ok('空范围领用记录页也为空，两个列表使用同一范围口径',
    r.json?.ok && Array.isArray(r.json.data) && r.json.data.length === 0, JSON.stringify(r.json))

  // ---------------------------------------------------------------- 2. 月末边界
  section('2. 跨月月末边界：月末和次月月初都不能漏')
  const boardRange = await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d2 })}`)
  const b1 = boardRange.json.data
  ok('跨月两天查询包含月末 d1 与次月月初 d2 三条记录',
    b1.records.length === 3
    && b1.records.some((x) => x.id === issueA_X.id && x.useDate === d1)
    && b1.records.some((x) => x.id === issueB_X.id && x.useDate === d2)
    && b1.records.some((x) => x.id === issueA_Y.id && x.useDate === d2),
    JSON.stringify(b1.records.map((x) => [x.id, x.useDate])))
  const b1Summary = (m, s) => b1.summaries.find((x) => x.materialId === m.id && x.studentId === s.id)
  ok('跨月汇总数字按材料+学员正确聚合',
    b1Summary(matX, stuA)?.issueQty === 2 && b1Summary(matX, stuA)?.returnQty === 0 && b1Summary(matX, stuA)?.netQty === 2
    && b1Summary(matX, stuB)?.issueQty === 3
    && b1Summary(matY, stuA)?.issueQty === 4,
    JSON.stringify(b1.summaries))

  const day1 = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d1 })}`)).json.data
  const day2 = (await api('GET', `/api/material-uses/board${qs({ startDate: d2, endDate: d2 })}`)).json.data
  ok('单日查询包含本地日期 00:00-23:59 的月末记录', day1.records.length === 1 && day1.records[0].id === issueA_X.id)
  ok('单日查询包含次月月初记录', day2.records.length === 2
    && day2.records.some((x) => x.id === issueB_X.id)
    && day2.records.some((x) => x.id === issueA_Y.id))
  const fullRange = await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3 })}`)
  ok('跨月完整范围包含 d3，不会把范围外记录混进汇总', fullRange.json.data.records.length === 4
    && fullRange.json.data.records.some((x) => x.id === issueB_Y.id))

  // ---------------------------------------------------------------- 3. 材料与学员交叉筛选
  section('3. 材料与学员交叉筛选：汇总和展开明细都遵守同一条件')
  const ax = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matX.id, studentId: stuA.id })}`)).json.data
  ok('材料 X + 学员 A 只剩 1 个汇总组和 1 条明细',
    ax.summaries.length === 1 && ax.records.length === 1
    && ax.summaries[0].materialId === matX.id && ax.summaries[0].studentId === stuA.id
    && ax.records[0].id === issueA_X.id, JSON.stringify(ax))
  const bx = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matX.id, studentId: stuB.id })}`)).json.data
  ok('材料 X + 学员 B 不混入其他材料/学员',
    bx.summaries.length === 1 && bx.records.length === 1 && bx.records[0].id === issueB_X.id)
  const ay = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matY.id, studentId: stuA.id })}`)).json.data
  ok('材料 Y + 学员 A 只返回 A 的 Y 材料记录',
    ay.summaries.length === 1 && ay.records[0].id === issueA_Y.id)
  const materialOnly = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matY.id })}`)).json.data
  ok('仅材料筛选返回该材料下不同学员的组',
    materialOnly.summaries.length === 2
    && materialOnly.records.every((x) => x.materialId === matY.id))
  const studentOnly = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, studentId: stuB.id })}`)).json.data
  ok('仅学员筛选返回该学员不同材料的组',
    studentOnly.summaries.length === 2
    && studentOnly.records.every((x) => x.studentId === stuB.id))

  const listCross = (await api('GET', `/api/material-uses${qs({ startDate: d1, endDate: d3, materialId: matX.id, studentId: stuA.id })}`)).json.data
  ok('领用记录页同一交叉筛选的记录与看板明细一致',
    listCross.length === 1 && listCross[0].id === issueA_X.id
    && listCross[0].studentName === stuA.name && listCross[0].materialName === matX.name)

  // ---------------------------------------------------------------- 4. 归还 / 净消耗 / 当前余量
  section('4. 归还后净消耗与当前余量变化，展开明细能追溯原领用')
  const issueR = await issue(stuA, matR, 5, d1)
  let returnResp = await api('POST', `/api/material-uses/${issueR.id}/return`, { qty: 2, returnDate: d3 })
  ok('部分归还成功并生成「归还」记录，库存加回 2',
    returnResp.json?.ok && returnResp.json.data.recordType === '归还'
    && returnResp.json.data.relatedUseId === issueR.id
    && returnResp.json.data.sourceUseDate === d2
    && returnResp.json.data.qty === 2, JSON.stringify(returnResp.json))
  const returnId = returnResp.json.data.id
  let matAfterReturn = (await api('GET', `/api/art-materials`)).json.data.find((m) => m.id === matR.id)
  ok('库存从 15 回到 17', matAfterReturn.stock === 17, `stock=${matAfterReturn.stock}`)

  let rb = (await api('GET', `/api/material-uses/board${qs({ startDate: d2, endDate: d3, materialId: matR.id, studentId: stuA.id })}`)).json.data
  let rs = rb.summaries[0]
  ok('同组汇总只统计范围内归还：领用 0、归还 2、净消耗 -2、当前余量 17',
    rb.summaries.length === 1 && rs.issueQty === 0 && rs.returnQty === 2 && rs.netQty === -2 && rs.currentStock === 17,
    JSON.stringify(rs))
  ok('汇总行可展开到范围内归还和范围外对应原领用两条记录',
    rb.records.length === 2
    && rb.records.some((x) => x.id === issueR.id && x.recordType === '领用' && x.inRange === false
      && x.returnedQty === 2 && x.outstandingQty === 3)
    && rb.records.some((x) => x.id === returnId && x.recordType === '归还' && x.inRange === true
      && x.sourceUseDate === d1),
    JSON.stringify(rb.records))

  returnResp = await api('POST', `/api/material-uses/${issueR.id}/return`, { qty: 2, returnDate: d3 })
  ok('再次归还后累计归还为 4', returnResp.json?.ok && returnResp.json.data.qty === 2)
  let failReturn = await api('POST', `/api/material-uses/${issueR.id}/return`, { qty: 2, returnDate: d3 })
  ok('不能超过原领用未归还数量（最多还剩 1）',
    failReturn.status === 400 && failReturn.json?.ok === false && /最多可还\s*1/.test(failReturn.json.message),
    JSON.stringify(failReturn.json))
  const earlyReturn = await api('POST', `/api/material-uses/${issueR.id}/return`, { qty: 1, returnDate: dateISO(plusDays(monthEnd, -1)) })
  ok('归还日期早于原领用日期被拦截', earlyReturn.status === 400 && /不能早于/.test(earlyReturn.json?.message))
  returnResp = await api('POST', `/api/material-uses/${issueR.id}/return`, { qty: 1, returnDate: d3 })
  ok('最后 1 件可归还，库存恢复到 20', returnResp.json?.ok)
  matAfterReturn = (await api('GET', `/api/art-materials`)).json.data.find((m) => m.id === matR.id)
  ok('全部归还后当前余量为 20、净消耗为 0', matAfterReturn.stock === 20, `stock=${matAfterReturn.stock}`)
  rb = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matR.id, studentId: stuA.id })}`)).json.data
  ok('最终汇总：领用 5、归还 5、净消耗 0',
    rb.summaries[0].issueQty === 5 && rb.summaries[0].returnQty === 5 && rb.summaries[0].netQty === 0
    && rb.summaries[0].currentStock === 20, JSON.stringify(rb.summaries[0]))

  // 跨期归还：d3 只看归还，净消耗为 -2，用于确认日期归属按归还日本地日期计算
  const returnOnly = (await api('GET', `/api/material-uses/board${qs({ startDate: d3, endDate: d3, materialId: matR.id, studentId: stuA.id })}`)).json.data
  ok('仅查询归还日期时净消耗可为负，跨期归还不漏记且展开带来源领用',
    returnOnly.summaries.length === 1 && returnOnly.summaries[0].issueQty === 0
    && returnOnly.summaries[0].returnQty === 5 && returnOnly.summaries[0].netQty === -5
    && returnOnly.summaries[0].currentStock === 20
    && returnOnly.records.some((x) => x.id === issueR.id && x.inRange === false)
    && returnOnly.records.filter((x) => x.inRange).every((x) => x.recordType === '归还'),
    JSON.stringify(returnOnly.summaries))

  // ---------------------------------------------------------------- 5. 删除学员历史追溯
  section('5. 删除学员后历史领用仍保留原姓名快照，且仍可归还')
  const issueD = await issue(stuC, matD, 3, d2)
  let deleteResp = await api('DELETE', `/api/students/${stuC.id}`)
  ok('仅有画材领用记录的学员允许删除，台账不阻断', deleteResp.json?.ok === true, JSON.stringify(deleteResp.json))
  const deletedBoard = (await api('GET', `/api/material-uses/board${qs({ startDate: d1, endDate: d3, materialId: matD.id, studentId: stuC.id })}`)).json.data
  ok('删除后按原学员 id 仍能在看板查到历史汇总与原姓名快照',
    deletedBoard.summaries.length === 1 && deletedBoard.summaries[0].studentName === stuC.name
    && deletedBoard.summaries[0].studentDeleted === true && deletedBoard.summaries[0].issueQty === 3,
    JSON.stringify(deletedBoard.summaries))
  ok('删除后看板明细保留原姓名快照',
    deletedBoard.records.length === 1 && deletedBoard.records[0].id === issueD.id
    && deletedBoard.records[0].studentName === stuC.name
    && deletedBoard.records[0].studentDeleted === true
    && deletedBoard.records[0].studentStatus == null,
    JSON.stringify(deletedBoard.records))
  const deletedList = (await api('GET', `/api/material-uses${qs({ studentId: stuC.id, materialId: matD.id })}`)).json.data
  ok('领用记录页也使用同一姓名快照口径',
    deletedList.length === 1 && deletedList[0].studentName === stuC.name && deletedList[0].studentDeleted === true,
    JSON.stringify(deletedList))
  const deletedReturn = await api('POST', `/api/material-uses/${issueD.id}/return`, { qty: 1, returnDate: d3 })
  ok('学员删除后仍可按原领用记录归还，归还行复制原姓名快照',
    deletedReturn.json?.ok && deletedReturn.json.data.studentName === stuC.name
    && deletedReturn.json.data.studentDeleted === true,
    JSON.stringify(deletedReturn.json))
  const matDNow = (await api('GET', '/api/art-materials')).json.data.find((m) => m.id === matD.id)
  ok('删除学员的归还仍正确回补库存（10 -> 7 -> 8）', matDNow.stock === 8, `stock=${matDNow.stock}`)

  const studentsAfterDelete = (await api('GET', '/api/students')).json.data
  ok('当前学员下拉源中不再出现已删除学员', !studentsAfterDelete.some((s) => s.id === stuC.id))
  const boardStudentOptions = (await api('GET', '/api/material-uses/student-options')).json.data
  ok('看板学员筛选项保留已删除学员并标记 deleted',
    boardStudentOptions.some((s) => s.id === stuC.id && s.name === stuC.name && s.deleted === true),
    JSON.stringify(boardStudentOptions.filter((s) => s.deleted)))

  // ---------------------------------------------------------------- 6. 连续筛选与失败
  section('6. 连续切换筛选：每组响应都只携带自身筛选结果；失败参数不会产生成功数据')
  const queries = [
    { name: 'X+A', query: { startDate: d1, endDate: d3, materialId: matX.id, studentId: stuA.id } },
    { name: 'Y+B', query: { startDate: d1, endDate: d3, materialId: matY.id, studentId: stuB.id } },
    { name: 'X all', query: { startDate: d1, endDate: d3, materialId: matX.id } },
    { name: 'B all', query: { startDate: d1, endDate: d3, studentId: stuB.id } },
    { name: 'R+A', query: { startDate: d1, endDate: d3, materialId: matR.id, studentId: stuA.id } }
  ]
  const responses = await Promise.all(queries.map(async (item, i) => {
    await sleep(i * 8)
    const rr = await api('GET', `/api/material-uses/board${qs(item.query)}`)
    return { ...item, data: rr.json?.data }
  }))
  const concurrentAllCorrect = responses.every(({ query, data }) =>
    data
    && data.records.every((x) =>
      (query.materialId ? x.materialId === query.materialId : true)
      && (query.studentId ? x.studentId === query.studentId : true)
      && x.useDate >= query.startDate && x.useDate <= query.endDate)
    && data.summaries.every((x) =>
      (query.materialId ? x.materialId === query.materialId : true)
      && (query.studentId ? x.studentId === query.studentId : true)))
  ok('并行/连续返回的 5 组结果均不包含其他筛选条件的数据', concurrentAllCorrect,
    JSON.stringify(responses.map(({ name, data }) => [name, data?.records.length, data?.summaries.length])))
  const expectedCounts = { 'X+A': 1, 'Y+B': 1, 'X all': 2, 'B all': 2, 'R+A': 4 }
  ok('各组明细数量与最新筛选一一对应',
    responses.every(({ name, data }) => data.records.length === expectedCounts[name]),
    JSON.stringify(responses.map(({ name, data }) => [name, data?.records.length])))

  const badDate = await api('GET', `/api/material-uses/board${qs({ startDate: 'not-a-date', endDate: d3 })}`)
  ok('非法日期请求失败，不返回任何看板数据', badDate.status === 400 && badDate.json?.ok === false && badDate.json.data == null,
    JSON.stringify(badDate.json))

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${'='.repeat(48)}`)
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); process.exit(1) }
  console.log('\x1b[32m画材消耗看板验收全部通过 ✅\x1b[0m')
}

main().catch((e) => { console.error('验收脚本异常：', e); process.exit(2) })

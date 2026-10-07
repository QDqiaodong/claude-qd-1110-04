import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

// 返回完整 body（含 message），用于需要区分「本次成功」与「重复请求幂等命中」的操作
async function callFull(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body
}

const params = (o) => ({ params: o })

// 领用 / 看板筛选参数：只带上非空条件，保证列表与汇总发往后端的是同一组口径
function useFilters(f = {}) {
  const p = {}
  if (f.from) p.from = f.from
  if (f.to) p.to = f.to
  if (f.materialId) p.materialId = f.materialId
  if (f.studentId) p.studentId = f.studentId
  return p
}

export default {
  // ---------------- 学员 ----------------
  listStudents: (status, level) => call(http.get('/students', params(
    status ? { status, ...(level ? { level } : {}) }
            : (level ? { level } : {})))),
  createStudent: (d) => call(http.post('/students', d)),
  updateStudent: (id, d) => call(http.put(`/students/${id}`, d)),
  deleteStudent: (id) => call(http.delete(`/students/${id}`)),

  // ---------------- 画材 ----------------
  listArtMaterials: (category, status) => call(http.get('/art-materials', params(
    category ? { category, ...(status ? { status } : {}) }
              : (status ? { status } : {})))),
  createArtMaterial: (d) => call(http.post('/art-materials', d)),
  updateArtMaterial: (id, d) => call(http.put(`/art-materials/${id}`, d)),

  // ---------------- 课程排期 ----------------
  listCourseSchedules: (studentId, status) => call(http.get('/course-schedules', params(
    studentId ? { studentId, ...(status ? { status } : {}) }
              : (status ? { status } : {})))),
  createCourseSchedule: (d) => call(http.post('/course-schedules', d)),
  cancelCourseSchedule: (id) => callFull(http.post(`/course-schedules/${id}/cancel`)),
  checkinCourseSchedule: (id) => call(http.post(`/course-schedules/${id}/checkin`)),

  // ---------------- 课后成果记录（按课次幂等） ----------------
  listCourseRecords: (studentId, status, scheduleId) => call(http.get('/course-records', params({
    ...(studentId ? { studentId } : {}),
    ...(status ? { status } : {}),
    ...(scheduleId ? { scheduleId } : {})
  }))),
  getCourseRecord: (scheduleId) => call(http.get(`/course-schedules/${scheduleId}/record`)),
  saveDraftRecord: (scheduleId, d) => call(http.post(`/course-schedules/${scheduleId}/record/draft`, d || {})),
  submitCourseRecord: (scheduleId, d) => callFull(http.post(`/course-schedules/${scheduleId}/record/submit`, d || {})),
  archiveCourseRecord: (scheduleId) => callFull(http.post(`/course-schedules/${scheduleId}/record/archive`)),
  deleteDraftRecord: (scheduleId) => call(http.delete(`/course-schedules/${scheduleId}/record`)),

  // ---------------- 领用记录 / 消耗看板（同一套筛选口径） ----------------
  // filters: { from, to, materialId, studentId }，from/to 为本地日期 YYYY-MM-DD（闭区间）
  listMaterialUses: (filters) => call(http.get('/material-uses', params(useFilters(filters)))),
  getMaterialUseSummary: (filters) => call(http.get('/material-uses/summary', params(useFilters(filters)))),
  createMaterialUse: (d) => call(http.post('/material-uses', d)),
  createMaterialReturn: (d) => call(http.post('/material-uses/returns', d))
}

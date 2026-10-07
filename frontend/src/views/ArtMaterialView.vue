<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const loading = ref(false)
const materials = ref([])
const category = ref('')
const status = ref('')

const CATS = ['颜料', '画笔', '画布', '工具']
const STATUS = ['在库', '停用']

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: null, name: '', category: '颜料', stock: 0, unit: '支', status: '在库' })

async function load() {
  loading.value = true
  try { materials.value = await api.listArtMaterials(category.value || null, status.value || null) }
  catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

function resetFilters() { category.value = ''; status.value = ''; load() }

function addMaterial() {
  editing.value = false
  Object.assign(form, { id: null, name: '', category: '颜料', stock: 0, unit: '支', status: '在库' })
  dialogVisible.value = true
}
function editMaterial(row) {
  editing.value = true
  Object.assign(form, { id: row.id, name: row.name, category: row.category, stock: row.stock, unit: row.unit, status: row.status })
  dialogVisible.value = true
}
async function saveMaterial() {
  try {
    if (editing.value) await api.updateArtMaterial(form.id, { ...form })
    else await api.createArtMaterial({ ...form })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}
async function toggleStatus(row) {
  const next = row.status === '在库' ? '停用' : '在库'
  try { await api.updateArtMaterial(row.id, { status: next }); ElMessage.success(next === '停用' ? '已停用' : '已启用'); await load(); emit('changed') }
  catch (e) { ElMessage.error(e.message) }
}

const statusType = (s) => (s === '在库' ? 'success' : 'info')

// ------------------------------------------------------------------
// 画材消耗看板：日期范围（本地日期闭区间）+ 画材 + 学员筛选；
// 汇总与展开明细、领用记录页共用后端同一套口径，不在前端二次过滤。
// ------------------------------------------------------------------
const students = ref([])

const dashRange = ref(null)          // [from, to]，YYYY-MM-DD 本地日期
const dashMaterialId = ref(null)
const dashStudentId = ref(null)

const dashLoading = ref(false)
const summaryRows = ref([])
const expandedKeys = ref([])         // 当前展开的汇总行 key
const details = ref({})              // key -> 该行的领用/归还明细
const detailLoading = ref({})        // key -> boolean

// 请求序号：连续切换筛选 / 刷新时，晚到的旧响应直接丢弃，
// 保证当前展示的汇总一定属于当前这一组筛选条件。
let dashSeq = 0

function dashFilters() {
  const [from, to] = dashRange.value || []
  return { from, to, materialId: dashMaterialId.value, studentId: dashStudentId.value }
}

async function loadSummary() {
  const seq = ++dashSeq
  dashLoading.value = true
  try {
    const data = await api.getMaterialUseSummary(dashFilters())
    if (seq !== dashSeq) return // 已被更新的筛选请求覆盖，旧汇总不得混入
    summaryRows.value = data.map((r) => ({ ...r, key: r.materialId + ':' + r.studentId }))
    // 筛选已变：上一组展开与明细缓存一并作废
    expandedKeys.value = []
    details.value = {}
    detailLoading.value = {}
  } catch (e) {
    if (seq !== dashSeq) return
    // 查询失败：保留筛选条件，清掉过期汇总，避免把旧数字当成当前结果
    summaryRows.value = []
    expandedKeys.value = []
    details.value = {}
    detailLoading.value = {}
    ElMessage.error(e.message)
  } finally {
    if (seq === dashSeq) dashLoading.value = false
  }
}

function resetDash() {
  dashRange.value = null
  dashMaterialId.value = null
  dashStudentId.value = null
  loadSummary()
}

// 展开汇总行 → 拉取同一筛选口径下该「画材 × 学员」的明细记录
async function onExpandChange(row, expandedRows) {
  expandedKeys.value = expandedRows.map((r) => r.key)
  if (!expandedKeys.value.includes(row.key) || details.value[row.key]) return
  const seq = dashSeq // 展开请求同样受序号保护：筛选切换后到达的旧明细直接丢弃
  detailLoading.value = { ...detailLoading.value, [row.key]: true }
  try {
    const list = await api.listMaterialUses({ ...dashFilters(), materialId: row.materialId, studentId: row.studentId })
    if (seq !== dashSeq) return
    details.value = { ...details.value, [row.key]: list }
  } catch (e) {
    if (seq === dashSeq) ElMessage.error(e.message)
  } finally {
    if (seq === dashSeq) {
      const next = { ...detailLoading.value }
      delete next[row.key]
      detailLoading.value = next
    }
  }
}

const kindType = (k) => (k === '归还' ? 'success' : 'warning')
const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

onMounted(async () => {
  load()
  try { students.value = await api.listStudents() } catch (e) { /* 学员筛选下拉加载失败不阻塞看板 */ }
  loadSummary()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <el-select v-model="category" placeholder="类别" clearable style="width:120px" @change="load">
          <el-option v-for="c in CATS" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="status" placeholder="状态" clearable style="width:120px" @change="load">
          <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <el-button type="primary" @click="addMaterial">新增画材</el-button>
    </div>

    <el-table :data="materials" v-loading="loading" border stripe>
      <el-table-column prop="name" label="画材名" />
      <el-table-column prop="category" label="类别" width="90">
        <template #default="{ row }"><el-tag>{{ row.category }}</el-tag></template>
      </el-table-column>
      <el-table-column label="库存" width="100">
        <template #default="{ row }">
          <span :class="{ 'low': row.stock <= 10 }">{{ row.stock }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="editMaterial(row)">编辑</el-button>
          <el-button link :type="row.status === '在库' ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === '在库' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- ==================== 画材消耗看板 ==================== -->
    <div class="dash">
      <div class="dash-head">
        <div>
          <div class="dash-title">画材消耗看板</div>
          <div class="dash-sub">按本地日期闭区间统计领用 / 归还 / 净消耗；当前余量为实时库存，与上方库存表同源；点击行首箭头展开明细。</div>
        </div>
        <div class="dash-filters">
          <el-date-picker
            v-model="dashRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始日期" end-placeholder="结束日期"
            style="width:250px" @change="loadSummary"
          />
          <el-select v-model="dashMaterialId" placeholder="画材" clearable filterable style="width:170px" @change="loadSummary">
            <el-option v-for="m in materials" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
          <el-select v-model="dashStudentId" placeholder="学员" clearable filterable style="width:140px" @change="loadSummary">
            <el-option v-for="s in students" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <el-button @click="resetDash">重置</el-button>
        </div>
      </div>

      <el-table
        :data="summaryRows" v-loading="dashLoading" border stripe
        row-key="key" :expand-row-keys="expandedKeys" @expand-change="onExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="dash-detail" v-loading="!!detailLoading[row.key]">
              <el-table :data="details[row.key] || []" size="small" border>
                <el-table-column prop="useDate" label="业务日期" width="110" />
                <el-table-column label="类型" width="80">
                  <template #default="{ row: d }"><el-tag size="small" :type="kindType(d.kind)">{{ d.kind }}</el-tag></template>
                </el-table-column>
                <el-table-column prop="qty" label="数量" width="80" />
                <el-table-column label="学员" width="120">
                  <template #default="{ row: d }">
                    {{ d.studentName }}
                    <el-tag v-if="d.studentDeleted" type="info" size="small" effect="plain">已删除</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="materialName" label="画材" min-width="140" />
                <el-table-column label="记录时间" width="150">
                  <template #default="{ row: d }">{{ fmtTime(d.createdAt) }}</template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="画材" min-width="160">
          <template #default="{ row }">
            {{ row.materialName }}
            <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="学员" width="130">
          <template #default="{ row }">
            {{ row.studentName }}
            <el-tag v-if="row.studentDeleted" type="info" size="small" effect="plain">已删除</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checkoutQty" label="领用数量" width="100" />
        <el-table-column prop="returnQty" label="归还数量" width="100" />
        <el-table-column label="净消耗" width="100">
          <template #default="{ row }"><b>{{ row.netQty }}</b></template>
        </el-table-column>
        <el-table-column label="当前余量" width="110">
          <template #default="{ row }">{{ row.currentStock }} {{ row.unit }}</template>
        </el-table-column>
        <template #empty>该时间范围内没有领用 / 归还记录</template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑画材' : '新增画材'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="画材名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category"><el-option v-for="c in CATS" :key="c" :label="c" :value="c" /></el-select>
        </el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.stock" :min="0" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in STATUS" :key="s" :label="s" :value="s" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMaterial">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; }
.low { color: #e06c75; font-weight: 700; }

.dash { margin-top: 24px; border-top: 1px solid var(--line); padding-top: 16px; }
.dash-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.dash-title { font-size: 15px; font-weight: 600; }
.dash-sub { color: var(--muted); font-size: 12px; margin-top: 4px; }
.dash-filters { display: flex; gap: 8px; flex-wrap: wrap; }
.dash-detail { padding: 4px 12px 12px 48px; background: var(--el-fill-color-lighter); }
</style>

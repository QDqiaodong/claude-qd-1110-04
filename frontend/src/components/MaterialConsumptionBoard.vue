<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const loading = ref(false)
const materials = ref([])
const students = ref([])
const board = ref(null)
const errorMessage = ref('')

const filters = reactive({
  dateRange: defaultDateRange(),
  materialId: null,
  studentId: null
})

let latestRequest = 0

function defaultDateRange() {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth(), 1)
  return [toDate(start), toDate(now)]
}
function toDate(d) {
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

async function loadOptions() {
  const [materialRows, studentRows] = await Promise.all([
    api.listArtMaterials(),
    api.listMaterialStudentOptions()
  ])
  materials.value = materialRows
  students.value = studentRows
}

function currentQuery() {
  const [startDate, endDate] = filters.dateRange || []
  return {
    startDate: startDate || '',
    endDate: endDate || '',
    materialId: filters.materialId,
    studentId: filters.studentId
  }
}

async function loadBoard() {
  const requestId = ++latestRequest
  const query = currentQuery()
  loading.value = true
  // 新查询开始即清掉上一组结果；失败时也保留空表，不能让过期汇总继续显示。
  board.value = null
  errorMessage.value = ''
  try {
    const data = await api.getMaterialBoard(query)
    if (requestId !== latestRequest) return
    board.value = data
    if (data.emptyRange) ElMessage.warning('开始日期晚于结束日期，没有可统计的记录')
  } catch (e) {
    if (requestId !== latestRequest) return
    errorMessage.value = e.message
    ElMessage.error(e.message)
  } finally {
    if (requestId === latestRequest) loading.value = false
  }
}

function resetFilters() {
  filters.dateRange = defaultDateRange()
  filters.materialId = null
  filters.studentId = null
  loadBoard()
}

function detailRows(row) {
  if (!board.value) return []
  return board.value.records.filter((r) =>
    r.materialId === row.materialId && r.studentId === row.studentId)
}

const summaryKey = (row) => `${row.materialId}-${row.studentId}`

const totalIssue = () => (board.value?.summaries || []).reduce((sum, x) => sum + x.issueQty, 0)
const totalReturn = () => (board.value?.summaries || []).reduce((sum, x) => sum + x.returnQty, 0)
const totalNet = () => totalIssue() - totalReturn()

onMounted(async () => {
  try {
    await loadOptions()
    await loadBoard()
  } catch (e) {
    errorMessage.value = e.message
    loading.value = false
  }
})
</script>

<template>
  <section class="consumption-board">
    <div class="board-head">
      <div>
        <h3>画材消耗看板</h3>
        <p>按本地日期统计领用、归还与净消耗；展开汇总行可核对同一口径下的台账记录。</p>
      </div>
    </div>

    <div class="board-filters">
      <el-date-picker
        v-model="filters.dateRange"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        unlink-panels
        @change="loadBoard"
      />
      <el-select v-model="filters.materialId" placeholder="全部材料" clearable filterable style="width:220px" @change="loadBoard">
        <el-option v-for="m in materials" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-select v-model="filters.studentId" placeholder="全部学员" clearable filterable style="width:180px" @change="loadBoard">
        <el-option
          v-for="s in students"
          :key="s.id"
          :label="s.deleted ? `${s.name}（已删除·快照）` : s.name"
          :value="s.id"
        />
      </el-select>
      <el-button type="primary" @click="loadBoard">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
    </div>

    <el-alert
      v-if="errorMessage"
      :title="'查询失败：' + errorMessage + '；筛选条件已保留，请调整后重试。'"
      type="error"
      show-icon
      :closable="false"
      class="board-alert"
    />

    <div class="board-totals">
      <span>领用合计 <b>{{ totalIssue() }}</b></span>
      <span>归还合计 <b>{{ totalReturn() }}</b></span>
      <span>净消耗合计 <b>{{ totalNet() }}</b></span>
    </div>

    <el-table
      :data="board?.summaries || []"
      v-loading="loading"
      border
      stripe
      :row-key="summaryKey"
      empty-text="所选范围内暂无领用或归还记录"
    >
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail-panel">
            <div class="detail-title">对应领用 / 归还记录（{{ detailRows(row).length }}）</div>
            <el-table :data="detailRows(row)" border size="small">
              <el-table-column label="范围" width="100">
                <template #default="{ row: r }">
                  <el-tag :type="r.inRange ? 'primary' : 'info'" size="small">{{ r.inRange ? '范围内' : '来源领用' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="recordType" label="类型" width="80">
                <template #default="{ row: r }">
                  <el-tag :type="r.recordType === '归还' ? 'success' : 'primary'">{{ r.recordType }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="学员" width="150">
                <template #default="{ row: r }">
                  {{ r.studentName }}
                  <el-tag v-if="r.studentDeleted" type="info" size="small">已删除·姓名快照</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="qty" label="数量" width="80" />
              <el-table-column prop="useDate" label="业务日期" width="120" />
              <el-table-column prop="sourceUseDate" label="原领用日期" width="120">
                <template #default="{ row: r }">{{ r.sourceUseDate || '—' }}</template>
              </el-table-column>
              <el-table-column prop="returnedQty" label="原领用累计已还" width="130">
                <template #default="{ row: r }">{{ r.recordType === '领用' ? r.returnedQty : '—' }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="materialName" label="材料" min-width="190" />
      <el-table-column prop="materialCategory" label="类别" width="90" />
      <el-table-column label="学员" min-width="170">
        <template #default="{ row }">
          {{ row.studentName }}
          <el-tag v-if="row.studentDeleted" type="info" size="small">已删除</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="issueQty" label="领用数量" width="100" />
      <el-table-column prop="returnQty" label="归还数量" width="100" />
      <el-table-column prop="netQty" label="净消耗" width="100">
        <template #default="{ row }">
          <span :class="row.netQty < 0 ? 'negative' : ''">{{ row.netQty }}</span>
        </template>
      </el-table-column>
      <el-table-column label="当前余量" width="120">
        <template #default="{ row }">{{ row.currentStock }} {{ row.materialUnit }}</template>
      </el-table-column>
      <el-table-column prop="recordCount" label="范围内记录" width="100" />
    </el-table>
  </section>
</template>

<style scoped>
.consumption-board {
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 10px rgba(47, 51, 64, 0.04);
}
.board-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px; }
.board-head h3 { margin: 0 0 4px; font-size: 17px; color: var(--primary-deep); }
.board-head p { margin: 0; color: var(--muted); font-size: 13px; }
.board-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.board-alert { margin: 12px 0; }
.board-totals { display: flex; gap: 18px; margin: 12px 0; color: var(--muted); font-size: 13px; }
.board-totals b { margin-left: 4px; color: var(--ink); font-size: 15px; }
.detail-panel { padding: 12px 18px; background: var(--bg); }
.detail-title { margin-bottom: 8px; font-weight: 600; color: var(--primary-deep); }
.negative { color: #e06c75; font-weight: 700; }
</style>

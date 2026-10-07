<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const loading = ref(false)
const uses = ref([])
const students = ref([])
const materials = ref([])

// 与消耗看板同一套筛选口径：日期范围（本地日期）+ 画材 + 学员
const filterRange = ref(null)
const filterMaterialId = ref(null)
const filterStudentId = ref(null)

// 请求序号：连续切换筛选时，晚到的旧响应直接丢弃，不会覆盖当前结果
let listSeq = 0

const dialogVisible = ref(false)
const form = reactive({ kind: '领用', studentId: null, materialId: null, qty: 1, useDate: '' })
const selectedMaterial = ref(null)

async function loadStudents() {
  students.value = await api.listStudents()
}
async function loadMaterials() {
  // 领用只涉及「在库」画材，但仍展示全部以便了解台账（归还不受「在库」限制）
  materials.value = await api.listArtMaterials()
}

function currentFilters() {
  const [from, to] = filterRange.value || []
  return { from, to, materialId: filterMaterialId.value, studentId: filterStudentId.value }
}

async function load() {
  const seq = ++listSeq
  loading.value = true
  try {
    const data = await api.listMaterialUses(currentFilters())
    if (seq !== listSeq) return // 已被更新的筛选请求覆盖
    uses.value = data
  } catch (e) {
    if (seq !== listSeq) return
    uses.value = [] // 查询失败：清掉过期列表，筛选条件保留
    ElMessage.error(e.message)
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

function resetFilters() {
  filterRange.value = null
  filterMaterialId.value = null
  filterStudentId.value = null
  load()
}

function openDialog(kind) {
  Object.assign(form, { kind, studentId: null, materialId: null, qty: 1, useDate: '' })
  selectedMaterial.value = null
  dialogVisible.value = true
}
function onMaterialChange(id) { selectedMaterial.value = materials.value.find((m) => m.id === id) || null }

async function saveUse() {
  const returning = form.kind === '归还'
  try {
    if (returning) await api.createMaterialReturn({ ...form })
    else await api.createMaterialUse({ ...form })
    ElMessage.success(returning ? '归还已登记，库存已回加' : '领用成功，库存已扣减')
    dialogVisible.value = false
    await Promise.all([load(), loadMaterials()]); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

const kindType = (k) => (k === '归还' ? 'success' : 'warning')
const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

onMounted(async () => { await Promise.all([loadStudents(), loadMaterials(), load()]) })
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <el-date-picker
          v-model="filterRange" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始日期" end-placeholder="结束日期"
          style="width:250px" @change="load"
        />
        <el-select v-model="filterMaterialId" placeholder="画材" clearable filterable style="width:170px" @change="load">
          <el-option v-for="m in materials" :key="m.id" :label="m.name" :value="m.id" />
        </el-select>
        <el-select v-model="filterStudentId" placeholder="学员" clearable filterable style="width:140px" @change="load">
          <el-option v-for="s in students" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <div>
        <el-button type="success" @click="openDialog('归还')">归还登记</el-button>
        <el-button type="primary" @click="openDialog('领用')">新增领用</el-button>
      </div>
    </div>
    <div class="hint-line">新建领用即扣减库存，归还登记即回加库存；停用或库存不足的画材领用时会被拦截。姓名为台账快照，学员删除后历史仍显示原姓名。</div>

    <el-table :data="uses" v-loading="loading" border stripe>
      <el-table-column label="学员" width="130">
        <template #default="{ row }">
          {{ row.studentName }}
          <el-tag v-if="row.studentDeleted" type="info" size="small" effect="plain">已删除</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="materialName" label="画材" min-width="150" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }"><el-tag :type="kindType(row.kind)">{{ row.kind }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" />
      <el-table-column prop="useDate" label="业务日期" width="120" />
      <el-table-column label="记录时间" width="150">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <template #empty>该时间范围内没有领用 / 归还记录</template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.kind === '归还' ? '归还登记' : '新增领用'" width="420px">
      <el-form label-width="80px">
        <el-form-item label="类型">
          <el-radio-group v-model="form.kind">
            <el-radio-button value="领用">领用</el-radio-button>
            <el-radio-button value="归还">归还</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="学员">
          <el-select v-model="form.studentId" placeholder="选择学员" filterable style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.code + '）'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="画材">
          <el-select v-model="form.materialId" placeholder="选择画材" filterable style="width:100%" @change="onMaterialChange">
            <el-option
              v-for="m in materials"
              :key="m.id"
              :label="m.name + '（剩 ' + m.stock + ' ' + m.unit + '，' + m.status + '）'"
              :value="m.id"
              :disabled="form.kind === '领用' && m.status !== '在库'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.qty" :min="1" />
          <span class="tip" v-if="selectedMaterial && form.kind === '领用'">
            剩余库存 {{ selectedMaterial.stock }} {{ selectedMaterial.unit }}
          </span>
          <span class="tip" v-else-if="selectedMaterial">归还后库存将回加 {{ form.qty }} {{ selectedMaterial.unit }}</span>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="form.useDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveUse">{{ form.kind === '归还' ? '确认归还' : '确认领用' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.filters { display: flex; gap: 8px; }
.hint-line { color: var(--muted); font-size: 13px; margin-bottom: 12px; }
.tip { margin-left: 10px; color: var(--muted); font-size: 12px; }
</style>

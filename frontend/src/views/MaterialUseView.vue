<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const loading = ref(false)
const uses = ref([])
const students = ref([])
const materials = ref([])

const issueDialogVisible = ref(false)
const returnDialogVisible = ref(false)
const issueForm = reactive({ studentId: null, materialId: null, qty: 1, useDate: '' })
const returnForm = reactive({ id: null, qty: 1, returnDate: '', maxQty: 0, materialName: '', studentName: '' })
const selectedMaterial = ref(null)

async function loadStudents() {
  students.value = await api.listStudents()
}
async function loadMaterials() {
  // 领用只涉及「在库」画材，但仍展示全部以便了解台账
  materials.value = await api.listArtMaterials()
}
async function load() {
  loading.value = true
  try { uses.value = await api.listMaterialUses() }
  catch (e) { uses.value = []; ElMessage.error(e.message) }
  finally { loading.value = false }
}

function addUse() {
  Object.assign(issueForm, { studentId: null, materialId: null, qty: 1, useDate: '' })
  selectedMaterial.value = null
  issueDialogVisible.value = true
}
function onMaterialChange(id) { selectedMaterial.value = materials.value.find((m) => m.id === id) || null }

async function saveUse() {
  try {
    await api.createMaterialUse({ ...issueForm })
    ElMessage.success('领用成功，库存已扣减')
    issueDialogVisible.value = false
    await Promise.all([load(), loadMaterials()])
    emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

function openReturn(row) {
  Object.assign(returnForm, {
    id: row.id,
    qty: row.outstandingQty,
    returnDate: '',
    maxQty: row.outstandingQty,
    materialName: row.materialName,
    studentName: row.studentName
  })
  returnDialogVisible.value = true
}

async function saveReturn() {
  try {
    const result = await api.returnMaterialUse(returnForm.id, {
      qty: returnForm.qty,
      returnDate: returnForm.returnDate || null
    })
    ElMessage.success(result.message || '归还成功，库存已增加')
    returnDialogVisible.value = false
    await Promise.all([load(), loadMaterials()])
    emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

onMounted(async () => {
  try { await Promise.all([loadStudents(), loadMaterials(), load()]) }
  catch (e) { ElMessage.error(e.message) }
})
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="hint">领用即扣减库存，归还按原领用记录回补；学员姓名和历史记录使用同一套台账口径。</span>
      <el-button type="primary" @click="addUse">新增领用</el-button>
    </div>

    <el-table :data="uses" v-loading="loading" border stripe>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.recordType === '归还' ? 'success' : 'primary'">{{ row.recordType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="学员" min-width="150">
        <template #default="{ row }">
          {{ row.studentName }}
          <el-tag v-if="row.studentDeleted" type="info" size="small">已删除·快照</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="画材" min-width="170">
        <template #default="{ row }">
          {{ row.materialName }}
          <span class="unit" v-if="row.materialUnit">（{{ row.materialUnit }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="领用数量" width="100">
        <template #default="{ row }">{{ row.recordType === '领用' ? row.qty : '—' }}</template>
      </el-table-column>
      <el-table-column label="归还数量" width="100">
        <template #default="{ row }">{{ row.recordType === '归还' ? row.qty : '—' }}</template>
      </el-table-column>
      <el-table-column prop="useDate" label="业务日期" width="120" />
      <el-table-column label="原领用日期" width="120">
        <template #default="{ row }">{{ row.sourceUseDate || '—' }}</template>
      </el-table-column>
      <el-table-column label="累计已还 / 未还" width="140">
        <template #default="{ row }">
          <template v-if="row.recordType === '领用'">
            {{ row.returnedQty }} / {{ row.outstandingQty }}
          </template>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="110">
        <template #default="{ row }">
          <el-button
            v-if="row.recordType === '领用' && row.outstandingQty > 0"
            link
            type="success"
            @click="openReturn(row)"
          >
            归还
          </el-button>
          <el-tag v-else-if="row.recordType === '领用'" type="success" size="small">已还清</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="issueDialogVisible" title="新增领用" width="420px">
      <el-form label-width="80px">
        <el-form-item label="学员">
          <el-select v-model="issueForm.studentId" placeholder="选择学员" filterable style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.code + '）'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="画材">
          <el-select v-model="issueForm.materialId" placeholder="选择在库画材" filterable style="width:100%" @change="onMaterialChange">
            <el-option
              v-for="m in materials"
              :key="m.id"
              :label="m.name + '（剩 ' + m.stock + ' ' + m.unit + '，' + m.status + '）'"
              :value="m.id"
              :disabled="m.status !== '在库'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="issueForm.qty" :min="1" />
          <span class="tip" v-if="selectedMaterial">
            剩余库存 {{ selectedMaterial.stock }} {{ selectedMaterial.unit }}
          </span>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="issueForm.useDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveUse">确认领用</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="returnDialogVisible" title="归还画材" width="420px">
      <el-form label-width="90px">
        <el-form-item label="原领用学员">
          <span>{{ returnForm.studentName }}</span>
        </el-form-item>
        <el-form-item label="画材">
          <span>{{ returnForm.materialName }}</span>
        </el-form-item>
        <el-form-item label="归还数量">
          <el-input-number v-model="returnForm.qty" :min="1" :max="returnForm.maxQty" />
          <span class="tip">最多可还 {{ returnForm.maxQty }}</span>
        </el-form-item>
        <el-form-item label="归还日期">
          <el-date-picker v-model="returnForm.returnDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveReturn">确认归还</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.tip { margin-left: 10px; color: var(--muted); font-size: 12px; }
.unit { color: var(--muted); font-size: 12px; }
</style>

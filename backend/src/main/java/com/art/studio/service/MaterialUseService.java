package com.art.studio.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.art.studio.common.BizException;
import com.art.studio.dto.MaterialUseSummary;
import com.art.studio.dto.MaterialUseView;
import com.art.studio.entity.ArtMaterial;
import com.art.studio.entity.MaterialUse;
import com.art.studio.entity.Student;
import com.art.studio.repository.ArtMaterialRepository;
import com.art.studio.repository.MaterialUseRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 领用 / 归还台账与画材消耗看板。
 *
 * 口径约定（看板汇总、看板展开明细、领用记录页三方共用）：
 *   1. 列表与汇总走同一个 search()，汇总就是这批记录在后端按「画材 × 学员」的分组合计，
 *      不存在前端二次过滤导致的对不上；
 *   2. 日期边界按本地日历日闭区间 [from, to] 匹配 use_date，结束日期当天整条计入，
 *      跨月查询不会漏掉月末记录；
 *   3. 学员姓名取台账快照，学员删除 / 改名后历史仍显示原姓名；
 *   4. 当前余量 = 画材台账实时库存，与库存页同一数字，不随日期范围变化。
 */
@Service
public class MaterialUseService {

    /** 空日期边界展开为全开区间，避免空参比较 */
    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final MaterialUseRepository repo;
    private final ArtMaterialRepository materialRepo;
    private final StudentRepository studentRepo;

    public MaterialUseService(MaterialUseRepository repo, ArtMaterialRepository materialRepo,
            StudentRepository studentRepo) {
        this.repo = repo;
        this.materialRepo = materialRepo;
        this.studentRepo = studentRepo;
    }

    // ---------------------------------------------------------------- 查询（列表与汇总同一口径）

    /** 领用记录页 / 看板展开明细：按筛选条件返回记录视图。 */
    public List<MaterialUseView> list(String from, String to, Long materialId, Long studentId) {
        List<MaterialUse> records = search(from, to, materialId, studentId);
        Map<Long, ArtMaterial> materials = materialsById();
        Map<Long, Student> students = studentsById(records);
        List<MaterialUseView> views = new ArrayList<>();
        for (MaterialUse u : records) {
            views.add(toView(u, materials.get(u.materialId), students.get(u.studentId)));
        }
        return views;
    }

    /**
     * 消耗看板汇总：对同一批筛选出的记录按「画材 × 学员」分组聚合。
     * 只输出范围内有记录的组合；空范围（无记录）返回空列表而非报错。
     */
    public List<MaterialUseSummary> summary(String from, String to, Long materialId, Long studentId) {
        List<MaterialUse> records = search(from, to, materialId, studentId);
        Map<Long, ArtMaterial> materials = materialsById();
        Map<Long, Student> students = studentsById(records);

        Map<String, MaterialUseSummary> grouped = new LinkedHashMap<>();
        for (MaterialUse u : records) {
            String key = u.materialId + ":" + u.studentId;
            MaterialUseSummary row = grouped.computeIfAbsent(key,
                    (k) -> newRow(u, materials.get(u.materialId), students.get(u.studentId)));
            if ("归还".equals(u.kind)) {
                row.returnQty += u.qty;
            } else {
                row.checkoutQty += u.qty;
            }
            row.netQty = row.checkoutQty - row.returnQty;
        }
        List<MaterialUseSummary> rows = new ArrayList<>(grouped.values());
        rows.sort((a, b) -> {
            int c = a.materialId.compareTo(b.materialId);
            return c != 0 ? c : a.studentId.compareTo(b.studentId);
        });
        return rows;
    }

    /** 列表与汇总共用的筛选入口：解析并校验日期边界后走同一条查询。 */
    private List<MaterialUse> search(String from, String to, Long materialId, Long studentId) {
        LocalDate fromDate = parseDate(from, "开始日期");
        LocalDate toDate = parseDate(to, "结束日期");
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        return repo.search(fromDate == null ? MIN_DATE : fromDate,
                toDate == null ? MAX_DATE : toDate, materialId, studentId);
    }

    private LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BizException(label + "格式应为 YYYY-MM-DD：" + value);
        }
    }

    // ---------------------------------------------------------------- 领用 / 归还

    /** 新建领用即扣库存；引用画材须「在库」，库存不足则拦截。 */
    @Transactional
    public MaterialUseView create(MaterialUse input) {
        Student student = requireStudent(input);
        ArtMaterial m = requireMaterial(input);
        if (!"在库".equals(m.status)) {
            throw new BizException("画材「" + m.name + "」已停用，不可领用");
        }
        requireQty(input);
        if (m.stock < input.qty) {
            throw new BizException("画材「" + m.name + "」库存不足（剩余 " + m.stock + "）");
        }
        m.stock -= input.qty;
        materialRepo.save(m);
        return toView(saveUse(input, student, "领用"), m, student);
    }

    /**
     * 归还登记：回加库存并留「归还」台账。停用画材也允许归还（只是回收入库）；
     * 数量只要求大于 0，不与单笔领用挂钩（净消耗在看板按范围合计）。
     */
    @Transactional
    public MaterialUseView createReturn(MaterialUse input) {
        Student student = requireStudent(input);
        ArtMaterial m = requireMaterial(input);
        requireQty(input);
        m.stock += input.qty;
        materialRepo.save(m);
        return toView(saveUse(input, student, "归还"), m, student);
    }

    private MaterialUse saveUse(MaterialUse input, Student student, String kind) {
        MaterialUse u = new MaterialUse();
        u.studentId = student.id;
        u.studentName = student.name;
        u.materialId = input.materialId;
        u.kind = kind;
        u.qty = input.qty;
        u.useDate = input.useDate == null ? LocalDate.now() : input.useDate;
        u.createdAt = LocalDateTime.now();
        return repo.save(u);
    }

    private Student requireStudent(MaterialUse input) {
        if (input == null || input.studentId == null) {
            throw new BizException("领用 / 归还必须指定学员");
        }
        return studentRepo.findById(input.studentId)
                .orElseThrow(() -> new BizException("学员不存在：" + input.studentId));
    }

    private ArtMaterial requireMaterial(MaterialUse input) {
        if (input.materialId == null) {
            throw new BizException("领用 / 归还必须指定画材");
        }
        return materialRepo.findById(input.materialId)
                .orElseThrow(() -> new BizException("画材不存在：" + input.materialId));
    }

    private void requireQty(MaterialUse input) {
        if (input.qty == null || input.qty <= 0) {
            throw new BizException("数量须大于 0");
        }
    }

    // ---------------------------------------------------------------- 视图装配

    private Map<Long, ArtMaterial> materialsById() {
        Map<Long, ArtMaterial> map = new HashMap<>();
        for (ArtMaterial m : materialRepo.findAll()) {
            map.put(m.id, m);
        }
        return map;
    }

    private Map<Long, Student> studentsById(List<MaterialUse> records) {
        Map<Long, Student> map = new HashMap<>();
        List<Long> ids = records.stream().map((u) -> u.studentId).distinct().toList();
        for (Student s : studentRepo.findAllById(ids)) {
            map.put(s.id, s);
        }
        return map;
    }

    private MaterialUseSummary newRow(MaterialUse u, ArtMaterial m, Student s) {
        MaterialUseSummary row = new MaterialUseSummary();
        row.materialId = u.materialId;
        row.studentId = u.studentId;
        row.studentName = displayName(u, s);
        row.studentDeleted = s == null;
        if (m != null) {
            row.materialName = m.name;
            row.category = m.category;
            row.unit = m.unit;
            row.materialStatus = m.status;
            row.currentStock = m.stock;
        }
        return row;
    }

    private MaterialUseView toView(MaterialUse u, ArtMaterial m, Student s) {
        MaterialUseView v = new MaterialUseView();
        v.id = u.id;
        v.studentId = u.studentId;
        v.studentName = displayName(u, s);
        v.studentDeleted = s == null;
        v.materialId = u.materialId;
        if (m != null) {
            v.materialName = m.name;
            v.materialUnit = m.unit;
        }
        v.kind = u.kind;
        v.qty = u.qty;
        v.useDate = u.useDate;
        v.createdAt = u.createdAt;
        return v;
    }

    /** 姓名口径：快照优先；早期无快照的记录退到学员现名；学员已删除且无快照时给可读占位。 */
    private String displayName(MaterialUse u, Student s) {
        if (u.studentName != null && !u.studentName.isBlank()) {
            return u.studentName;
        }
        return s != null ? s.name : "学员#" + u.studentId;
    }
}

package com.art.studio.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.art.studio.common.BizException;
import com.art.studio.dto.MaterialBoardSummary;
import com.art.studio.dto.MaterialBoardView;
import com.art.studio.dto.MaterialReturnRequest;
import com.art.studio.dto.MaterialStudentOption;
import com.art.studio.dto.MaterialUseView;
import com.art.studio.entity.ArtMaterial;
import com.art.studio.entity.MaterialUse;
import com.art.studio.entity.Student;
import com.art.studio.repository.ArtMaterialRepository;
import com.art.studio.repository.MaterialUseRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MaterialUseService {

    private final MaterialUseRepository repo;
    private final ArtMaterialRepository materialRepo;
    private final StudentRepository studentRepo;

    public MaterialUseService(MaterialUseRepository repo, ArtMaterialRepository materialRepo,
            StudentRepository studentRepo) {
        this.repo = repo;
        this.materialRepo = materialRepo;
        this.studentRepo = studentRepo;
    }

    @Transactional(readOnly = true)
    public List<MaterialUseView> list(Long studentId, Long materialId, LocalDate startDate, LocalDate endDate) {
        boolean emptyRange = isEmptyRange(startDate, endDate);
        if (emptyRange) {
            return List.of();
        }
        List<MaterialUse> rows = repo.findAll(spec(studentId, materialId, startDate, endDate),
                Sort.by(Sort.Order.desc("useDate"), Sort.Order.desc("id")));
        return toViews(rows);
    }

    /** 看板汇总和展开明细使用同一次台账查询，保证数字与记录一致。 */
    @Transactional(readOnly = true)
    public MaterialBoardView board(Long materialId, Long studentId, LocalDate startDate, LocalDate endDate) {
        MaterialBoardView result = new MaterialBoardView();
        boolean emptyRange = isEmptyRange(startDate, endDate);
        result.emptyRange = emptyRange;
        if (emptyRange) {
            result.summaries = List.of();
            result.records = List.of();
            return result;
        }

        List<MaterialUse> rows = repo.findAll(spec(studentId, materialId, startDate, endDate),
                Sort.by(Sort.Order.desc("useDate"), Sort.Order.desc("id")));
        List<MaterialUseView> rangeViews = toViews(rows);

        // 归还记录落在范围内时，展开明细还要带出范围外的原领用；汇总只统计真正在范围内的记录。
        Set<Long> rowIds = rows.stream().map(r -> r.id).collect(Collectors.toSet());
        List<Long> sourceIssueIds = rows.stream()
                .filter(r -> "归还".equals(r.recordType) && r.relatedUseId != null && !rowIds.contains(r.relatedUseId))
                .map(r -> r.relatedUseId)
                .distinct()
                .toList();
        List<MaterialUseView> sourceViews = toViews(repo.findAllById(sourceIssueIds));
        sourceViews.forEach(v -> v.inRange = false);

        List<MaterialUseView> allViews = new ArrayList<>(rangeViews.size() + sourceViews.size());
        allViews.addAll(rangeViews);
        allViews.addAll(sourceViews);
        allViews.sort(Comparator.comparing((MaterialUseView v) -> v.useDate, Comparator.reverseOrder())
                .thenComparing((MaterialUseView v) -> v.id, Comparator.reverseOrder()));

        result.records = allViews;
        result.summaries = buildSummaries(rangeViews);
        return result;
    }

    /** 新建领用即扣库存；引用画材须「在库」，库存不足则拦截。 */
    @Transactional
    public MaterialUseView create(MaterialUse input) {
        if (input.studentId == null) {
            throw new BizException("领用必须指定学员");
        }
        Student student = studentRepo.findById(input.studentId)
                .orElseThrow(() -> new BizException("学员不存在：" + input.studentId));
        if (input.materialId == null) {
            throw new BizException("领用必须指定画材");
        }
        ArtMaterial m = materialRepo.findByIdForUpdate(input.materialId)
                .orElseThrow(() -> new BizException("画材不存在：" + input.materialId));
        if (!"在库".equals(m.status)) {
            throw new BizException("画材「" + m.name + "」已停用，不可领用");
        }
        if (input.qty == null || input.qty <= 0) {
            throw new BizException("领用数量须大于 0");
        }
        if (m.stock < input.qty) {
            throw new BizException("画材「" + m.name + "」库存不足（剩余 " + m.stock + "）");
        }
        m.stock -= input.qty;
        materialRepo.save(m);

        MaterialUse u = new MaterialUse();
        u.studentId = student.id;
        u.studentName = student.name;
        u.materialId = input.materialId;
        u.recordType = "领用";
        u.qty = input.qty;
        u.useDate = input.useDate == null ? LocalDate.now() : input.useDate;
        u.createdAt = LocalDateTime.now();
        MaterialUse saved = repo.save(u);
        return toViews(List.of(saved)).get(0);
    }

    /** 归还：把可用库存加回，并留下和原领用关联的归还台账。 */
    @Transactional
    public MaterialUseView createReturn(Long useId, MaterialReturnRequest input) {
        if (input == null || input.qty == null || input.qty <= 0) {
            throw new BizException("归还数量须大于 0");
        }

        MaterialUse issue = repo.findIssueByIdForUpdate(useId)
                .orElseThrow(() -> new BizException("原领用记录不存在：" + useId));
        ArtMaterial material = materialRepo.findByIdForUpdate(issue.materialId)
                .orElseThrow(() -> new BizException("画材不存在：" + issue.materialId));
        int returned = sumReturnedQty(issue.id);
        int outstanding = issue.qty - returned;
        if (input.qty > outstanding) {
            throw new BizException("归还数量不能超过未归还数量（本次最多可还 " + outstanding + "）");
        }

        LocalDate returnDate = input.returnDate == null ? LocalDate.now() : input.returnDate;
        if (returnDate.isBefore(issue.useDate)) {
            throw new BizException("归还日期不能早于原领用日期");
        }

        material.stock += input.qty;
        materialRepo.save(material);

        MaterialUse record = new MaterialUse();
        record.studentId = issue.studentId;
        record.studentName = issue.studentName;
        record.materialId = issue.materialId;
        record.recordType = "归还";
        record.qty = input.qty;
        record.useDate = returnDate;
        record.relatedUseId = issue.id;
        record.createdAt = LocalDateTime.now();
        MaterialUse saved = repo.save(record);
        return toViews(List.of(saved)).get(0);
    }

    /** 看板学员筛选要包含已删除但仍有台账的学员；当前学员显示当前姓名，已删除学员显示最新快照。 */
    @Transactional(readOnly = true)
    public List<MaterialStudentOption> studentOptions() {
        Map<Long, Student> currentStudents = studentRepo.findAll().stream()
                .collect(Collectors.toMap(s -> s.id, Function.identity()));
        Map<Long, String> deletedSnapshots = new HashMap<>();
        for (MaterialUse row : repo.findAll()) {
            if (!currentStudents.containsKey(row.studentId)) {
                deletedSnapshots.putIfAbsent(row.studentId, row.studentName);
            }
        }

        List<MaterialStudentOption> options = new ArrayList<>();
        currentStudents.forEach((id, student) -> {
            MaterialStudentOption option = new MaterialStudentOption();
            option.id = id;
            option.name = student.name;
            option.deleted = false;
            options.add(option);
        });
        deletedSnapshots.forEach((id, name) -> {
            MaterialStudentOption option = new MaterialStudentOption();
            option.id = id;
            option.name = name;
            option.deleted = true;
            options.add(option);
        });
        options.sort(Comparator.comparing(o -> o.id));
        return options;
    }

    private Specification<MaterialUse> spec(Long studentId, Long materialId, LocalDate startDate, LocalDate endDate) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (studentId != null) {
                predicates.add(cb.equal(root.get("studentId"), studentId));
            }
            if (materialId != null) {
                predicates.add(cb.equal(root.get("materialId"), materialId));
            }
            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("useDate"), startDate));
            }
            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("useDate"), endDate));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }

    private List<MaterialBoardSummary> buildSummaries(List<MaterialUseView> views) {
        Map<Long, ArtMaterial> materialMap = materialRepo.findAllById(materialIdsFromViews(views)).stream()
                .collect(Collectors.toMap(m -> m.id, Function.identity()));
        Map<String, List<MaterialUseView>> groups = views.stream()
                .collect(Collectors.groupingBy(v -> v.materialId + "#" + v.studentId));

        List<MaterialBoardSummary> summaries = new ArrayList<>();
        for (List<MaterialUseView> group : groups.values()) {
            MaterialUseView first = group.get(0);
            int issue = group.stream()
                    .filter(v -> "领用".equals(v.recordType))
                    .mapToInt(v -> v.qty)
                    .sum();
            int returned = group.stream()
                    .filter(v -> "归还".equals(v.recordType))
                    .mapToInt(v -> v.qty)
                    .sum();
            ArtMaterial material = materialMap.get(first.materialId);
            MaterialBoardSummary s = new MaterialBoardSummary();
            s.materialId = first.materialId;
            s.materialName = first.materialName;
            s.materialCategory = first.materialCategory;
            s.materialUnit = first.materialUnit;
            s.studentId = first.studentId;
            s.studentName = first.studentName;
            s.studentDeleted = first.studentDeleted;
            s.issueQty = issue;
            s.returnQty = returned;
            s.netQty = issue - returned;
            s.currentStock = material == null ? 0 : material.stock;
            s.recordCount = group.size();
            summaries.add(s);
        }

        summaries.sort(Comparator.comparing((MaterialBoardSummary s) -> s.materialName == null ? "" : s.materialName)
                .thenComparing(s -> s.studentName == null ? "" : s.studentName)
                .thenComparing(s -> s.materialId)
                .thenComparing(s -> s.studentId));
        return summaries;
    }

    private List<MaterialUseView> toViews(List<MaterialUse> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<Long> materialIds = materialIds(rows);
        Set<Long> studentIds = rows.stream().map(r -> r.studentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> issueIds = rows.stream()
                .filter(r -> r.relatedUseId != null)
                .map(r -> r.relatedUseId)
                .collect(Collectors.toSet());
        Set<Long> viewIssueIds = rows.stream()
                .filter(r -> "领用".equals(r.recordType))
                .map(r -> r.id)
                .collect(Collectors.toSet());

        Map<Long, ArtMaterial> materials = materialRepo.findAllById(materialIds).stream()
                .collect(Collectors.toMap(m -> m.id, Function.identity()));
        Map<Long, Student> students = studentIds.isEmpty()
                ? Map.of()
                : studentRepo.findAllById(studentIds).stream().collect(Collectors.toMap(s -> s.id, Function.identity()));
        Map<Long, MaterialUse> issueMap = issueIds.isEmpty()
                ? Map.of()
                : repo.findAllById(issueIds).stream().collect(Collectors.toMap(r -> r.id, Function.identity()));

        Set<Long> issueIdsToLoad = new HashSet<>(issueIds);
        issueIdsToLoad.addAll(viewIssueIds);
        Map<Long, Integer> returnedMap = sumReturnedByIssue(issueIdsToLoad);

        List<MaterialUseView> views = new ArrayList<>(rows.size());
        for (MaterialUse r : rows) {
            MaterialUseView v = new MaterialUseView();
            ArtMaterial material = materials.get(r.materialId);
            Optional<Student> currentStudent = Optional.ofNullable(students.get(r.studentId));
            MaterialUse sourceIssue = issueMap.get(r.relatedUseId);

            v.id = r.id;
            v.studentId = r.studentId;
            v.studentName = r.studentName;
            v.studentStatus = currentStudent.map(s -> s.status).orElse(null);
            v.studentDeleted = currentStudent.isEmpty();
            v.materialId = r.materialId;
            v.materialName = material == null ? "已删除画材#" + r.materialId : material.name;
            v.materialCategory = material == null ? null : material.category;
            v.materialUnit = material == null ? null : material.unit;
            v.recordType = r.recordType;
            v.qty = r.qty;
            v.useDate = r.useDate;
            v.relatedUseId = r.relatedUseId;
            v.sourceUseDate = sourceIssue == null ? null : sourceIssue.useDate;
            if ("领用".equals(r.recordType)) {
                int returnedQty = returnedMap.getOrDefault(r.id, 0);
                v.returnedQty = returnedQty;
                v.outstandingQty = Math.max(0, r.qty - returnedQty);
            } else {
                v.returnedQty = 0;
                v.outstandingQty = 0;
            }
            v.createdAt = r.createdAt;
            views.add(v);
        }
        return views;
    }

    private int sumReturnedQty(Long issueId) {
        return repo.findByRelatedUseIdInAndRecordType(List.of(issueId), "归还").stream()
                .mapToInt(r -> r.qty)
                .sum();
    }

    private Map<Long, Integer> sumReturnedByIssue(Set<Long> issueIds) {
        if (issueIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        for (MaterialUse returnRow : repo.findByRelatedUseIdInAndRecordType(issueIds, "归还")) {
            result.merge(returnRow.relatedUseId, returnRow.qty, Integer::sum);
        }
        return result;
    }

    private Set<Long> materialIds(List<? extends MaterialUse> rows) {
        return rows.stream().map(r -> r.materialId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Set<Long> materialIdsFromViews(List<MaterialUseView> rows) {
        return rows.stream().map(r -> r.materialId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private boolean isEmptyRange(LocalDate startDate, LocalDate endDate) {
        return startDate != null && endDate != null && startDate.isAfter(endDate);
    }
}

package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.common.Dict;
import com.art.studio.entity.Student;
import com.art.studio.repository.CourseRecordRepository;
import com.art.studio.repository.CourseScheduleRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StudentService {

    private final StudentRepository repo;
    private final CourseScheduleRepository scheduleRepo;
    private final CourseRecordRepository recordRepo;

    public StudentService(StudentRepository repo, CourseScheduleRepository scheduleRepo,
            CourseRecordRepository recordRepo) {
        this.repo = repo;
        this.scheduleRepo = scheduleRepo;
        this.recordRepo = recordRepo;
    }

    public List<Student> list(String status, String level) {
        if (status != null && level != null) {
            return repo.findByStatusAndLevel(status, level);
        }
        if (status != null) {
            return repo.findByStatus(status);
        }
        if (level != null) {
            return repo.findByLevel(level);
        }
        return repo.findAll();
    }

    public Student get(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException("学员不存在：" + id));
    }

    public Student create(Student input) {
        if (input.code == null || input.code.isBlank()) {
            throw new BizException("学号不能为空");
        }
        if (repo.findByCode(input.code).isPresent()) {
            throw new BizException("学号已存在：" + input.code);
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("学员姓名不能为空");
        }
        if (input.phone != null && !input.phone.isBlank()
                && !input.phone.matches("1[3-9]\\d{9}")) {
            throw new BizException("手机号格式不正确（应为 1[3-9] 开头的 11 位号码）");
        }
        String level = input.level == null ? Dict.STUDENT_LEVELS.get(0) : input.level;
        if (!Dict.isValidStudentLevel(level)) {
            throw new BizException("学员级别不合法：" + level);
        }
        String status = input.status == null ? "在读" : input.status;
        if (!Dict.isValidStudentStatus(status)) {
            throw new BizException("学员状态不合法：" + status);
        }
        Student s = new Student();
        s.code = input.code;
        s.name = input.name;
        s.phone = input.phone;
        s.level = level;
        s.status = status;
        s.createdAt = LocalDateTime.now();
        return repo.save(s);
    }

    public Student update(Long id, Student input) {
        Student s = get(id);
        if (input.code != null) {
            if (!input.code.equals(s.code) && repo.findByCode(input.code).isPresent()) {
                throw new BizException("学号已存在：" + input.code);
            }
            s.code = input.code;
        }
        if (input.name != null) {
            s.name = input.name;
        }
        if (input.phone != null) {
            if (!input.phone.isBlank() && !input.phone.matches("1[3-9]\\d{9}")) {
                throw new BizException("手机号格式不正确（应为 1[3-9] 开头的 11 位号码）");
            }
            s.phone = input.phone;
        }
        if (input.level != null) {
            if (!Dict.isValidStudentLevel(input.level)) {
                throw new BizException("学员级别不合法：" + input.level);
            }
            s.level = input.level;
        }
        if (input.status != null) {
            if (!Dict.isValidStudentStatus(input.status)) {
                throw new BizException("学员状态不合法：" + input.status);
            }
            s.status = input.status;
        }
        return repo.save(s);
    }

    /**
     * 删除学员：排课 / 课后记录仍阻止删除；画材领用台账不做数据库外键，
     * 并保存领用当时的姓名快照，因此仅有领用记录的学员可以删除且历史仍可追溯。
     * 业务上更推荐把状态改为「休学 / 结业」而不是物理删除。
     */
    @Transactional
    public void delete(Long id) {
        Student s = get(id);
        long schedules = scheduleRepo.findByStudentId(id).size();
        long records = recordRepo.findByStudentId(id).size();
        if (schedules > 0 || records > 0) {
            throw new BizException("学员「" + s.name + "」已有历史业务数据（排课 " + schedules
                    + " 条、课后记录 " + records
                    + " 条），不能删除以免破坏课程历史；如需停用请将状态改为「休学」或「结业」");
        }
        repo.delete(s);
    }
}

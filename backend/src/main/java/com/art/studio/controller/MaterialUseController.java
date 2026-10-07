package com.art.studio.controller;

import java.time.LocalDate;
import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.dto.MaterialBoardView;
import com.art.studio.dto.MaterialReturnRequest;
import com.art.studio.dto.MaterialStudentOption;
import com.art.studio.dto.MaterialUseView;
import com.art.studio.entity.MaterialUse;
import com.art.studio.service.MaterialUseService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/material-uses")
public class MaterialUseController {

    private final MaterialUseService service;

    public MaterialUseController(MaterialUseService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<MaterialUseView>> list(
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(service.list(studentId, materialId, startDate, endDate));
    }

    @GetMapping("/student-options")
    public ApiResponse<List<MaterialStudentOption>> studentOptions() {
        return ApiResponse.success(service.studentOptions());
    }

    @GetMapping("/board")
    public ApiResponse<MaterialBoardView> board(
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.success(service.board(materialId, studentId, startDate, endDate));
    }

    @PostMapping
    public ApiResponse<MaterialUseView> create(@RequestBody MaterialUse input) {
        return ApiResponse.success(service.create(input));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<MaterialUseView> createReturn(
            @PathVariable Long id,
            @RequestBody(required = false) MaterialReturnRequest input) {
        return ApiResponse.success("归还成功，库存已增加", service.createReturn(id, input));
    }
}

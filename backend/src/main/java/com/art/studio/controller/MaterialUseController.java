package com.art.studio.controller;

import java.util.List;

import com.art.studio.common.ApiResponse;
import com.art.studio.dto.MaterialUseSummary;
import com.art.studio.dto.MaterialUseView;
import com.art.studio.entity.MaterialUse;
import com.art.studio.service.MaterialUseService;

import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * 领用记录列表（领用记录页与看板展开明细共用）。
     * from / to 为本地日期 YYYY-MM-DD，闭区间；缺省不限。
     */
    @GetMapping
    public ApiResponse<List<MaterialUseView>> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(service.list(from, to, materialId, studentId));
    }

    /** 画材消耗看板汇总：与列表同一套筛选口径，按「画材 × 学员」聚合。 */
    @GetMapping("/summary")
    public ApiResponse<List<MaterialUseSummary>> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long materialId,
            @RequestParam(required = false) Long studentId) {
        return ApiResponse.success(service.summary(from, to, materialId, studentId));
    }

    /** 新增领用（即扣库存）。 */
    @PostMapping
    public ApiResponse<MaterialUseView> create(@RequestBody MaterialUse input) {
        return ApiResponse.success(service.create(input));
    }

    /** 归还登记（回加库存，净消耗相应减少）。 */
    @PostMapping("/returns")
    public ApiResponse<MaterialUseView> createReturn(@RequestBody MaterialUse input) {
        return ApiResponse.success(service.createReturn(input));
    }
}

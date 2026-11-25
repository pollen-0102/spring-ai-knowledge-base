package com.pollen.rag.kb;

import com.pollen.rag.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kbs")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    @PostMapping
    public ApiResponse<KbView> create(@Valid @RequestBody KbDtos.CreateKbRequest request) {
        return ApiResponse.ok(kbService.create(request));
    }

    @GetMapping
    public ApiResponse<List<KbView>> list() {
        return ApiResponse.ok(kbService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<KbView> get(@PathVariable long id) {
        return ApiResponse.ok(kbService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<KbView> update(@PathVariable long id,
                                      @Valid @RequestBody KbDtos.UpdateKbRequest request) {
        return ApiResponse.ok(kbService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable long id) {
        kbService.delete(id);
        return ApiResponse.ok(Map.of("id", id, "deleted", true));
    }
}

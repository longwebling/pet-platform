package com.pethelp.arrangement.controller;

import com.pethelp.arrangement.dto.ArrangementResponse;
import com.pethelp.arrangement.dto.HandoverConfirmResponse;
import com.pethelp.arrangement.service.ArrangementService;
import com.pethelp.common.ApiResponse;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AdoptionArrangementController {

    private final ArrangementService arrangementService;

    public AdoptionArrangementController(ArrangementService arrangementService) {
        this.arrangementService = arrangementService;
    }

    @PostMapping("/adoption-arrangements/{id}/confirm-handover")
    public ApiResponse<HandoverConfirmResponse> confirmHandover(@PathVariable Long id,
                                                                @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(arrangementService.confirmHandover(id, currentUser.id()));
    }

    @PostMapping("/adoption-arrangements/{id}/cancel")
    public ApiResponse<ArrangementResponse> cancel(@PathVariable Long id,
                                                   @AuthenticationPrincipal CurrentUser currentUser) {
        return ApiResponse.ok(arrangementService.cancel(id, currentUser.id()));
    }

    @PostMapping("/adoption-arrangements/{id}/abort")
    public ApiResponse<ArrangementResponse> abort(@PathVariable Long id,
                                                  @AuthenticationPrincipal CurrentUser currentUser,
                                                  @RequestParam(required = false) String reason) {
        return ApiResponse.ok(arrangementService.abort(id, currentUser.id(), reason));
    }
}

package com.pethelp.message.controller;

import com.pethelp.common.ApiResponse;
import com.pethelp.message.dto.ConversationResponse;
import com.pethelp.message.dto.MessageResponse;
import com.pethelp.message.dto.SendMessageRequest;
import com.pethelp.message.service.ConversationService;
import com.pethelp.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationResponse>> listConversations(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(conversationService.listConversations(currentUser.id(), page, size));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<MessageResponse>> listMessages(@PathVariable Long id,
                                                           @AuthenticationPrincipal CurrentUser currentUser,
                                                           @RequestParam(name = "before_id", required = false) Long beforeId,
                                                           @RequestParam(name = "after_id", required = false) Long afterId,
                                                           @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(conversationService.listMessages(currentUser.id(), id, beforeId, afterId, limit));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<MessageResponse> sendMessage(@PathVariable Long id,
                                                    @AuthenticationPrincipal CurrentUser currentUser,
                                                    @RequestBody SendMessageRequest request) {
        return ApiResponse.ok(conversationService.sendMessage(currentUser.id(), id, request));
    }
}

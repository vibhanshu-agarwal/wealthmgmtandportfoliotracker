package com.wealth.insight;

import com.wealth.insight.dto.ChatRequest;
import com.wealth.insight.dto.ChatResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless conversational REST endpoint for market insight queries.
 *
 * <p>Delegates entirely to {@link ChatResolutionService} which owns the full resolution turn:
 * catalog-validated explicit ticker, deterministic preflight normalization, discovery shortcut,
 * LLM asset resolution, catalog validation of LLM proposals, intent branching, and response
 * building (Task 10 / Req 1.1, 6.5, 11.2).
 *
 * <p>The legacy regex {@code resolveTicker} pipeline has been removed and superseded by the
 * LLM-driven orchestration in {@link ChatResolutionService}.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatResolutionService chatResolutionService;

    public ChatController(ChatResolutionService chatResolutionService) {
        this.chatResolutionService = chatResolutionService;
    }

    /**
     * Handles a single conversational turn. Preserves the existing endpoint contract
     * ({@code POST /api/chat}, {@link ChatRequest} / {@link ChatResponse}).
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatResolutionService.handle(request));
    }
}

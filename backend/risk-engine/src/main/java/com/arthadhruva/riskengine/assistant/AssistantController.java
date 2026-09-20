package com.arthadhruva.riskengine.assistant;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ANALYST/ADMIN only (the default access rule). Deliberately NOT covered by {@code AuditAspect}
 * -- not for secrecy, but because a free-text question and an LLM's free-text answer aren't the
 * structured, replayable request/response shape the audit trail is designed for (see that
 * class's doc for the other, credential-related exclusions it already lists).
 */
@RestController
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping("/assistant/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String answer = assistantService.answer(TenantContext.get(), request.loanId(), request.question());
        return new ChatResponse(answer);
    }

    public record ChatRequest(String loanId, @NotBlank String question) {
    }

    public record ChatResponse(String answer) {
    }
}

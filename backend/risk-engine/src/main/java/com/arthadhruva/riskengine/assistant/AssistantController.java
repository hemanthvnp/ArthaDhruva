package com.arthadhruva.riskengine.assistant;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * ANALYST/ADMIN only (the default access rule). Audited like every other model call: who asked what
 * about which loan, and what the model answered, is exactly what a reviewer of AI use will ask for.
 */
@RestController
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Metered("ASSISTANT_CALL")
    @PostMapping("/assistant/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        AuditContext.modelVersion("llm:" + assistantService.model());
        String answer = assistantService.answer(TenantContext.get(), request.loanId(), request.question());
        return new ChatResponse(answer);
    }

    public record ChatRequest(@Size(max = 80) String loanId, @NotBlank @Size(max = 2000) String question) {
    }

    public record ChatResponse(String answer) {
    }
}

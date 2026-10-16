package com.arthadhruva.riskengine.assistant;

import com.arthadhruva.riskengine.audit.AuditContext;
import com.arthadhruva.riskengine.billing.Metered;
import com.arthadhruva.riskengine.tenant.TenantContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower safety assistant, under /my/** (SecurityConfig): any signed-in ANALYST/ADMIN/CLIENT, answering
 * only about the caller's own loans. Separate from {@link AssistantController} (ANALYST/ADMIN, any loan in the
 * tenant, grounded in case notes), so a change to either cannot reach the other.
 *
 * <p>Audited like every controller call; its rows carry {@code endpoint = BorrowerAssistantController.ask}
 * where the analyst assistant's carry {@code AssistantController.chat}, which is how a reviewer tells the two
 * apart, and it is metered as its own usage metric.
 */
@RestController
public class BorrowerAssistantController {

    private final BorrowerAssistantService assistant;

    public BorrowerAssistantController(BorrowerAssistantService assistant) {
        this.assistant = assistant;
    }

    @Metered("BORROWER_ASSISTANT_CALL")
    @PostMapping("/my/assistant")
    public BorrowerAssistantService.Answer ask(@Valid @RequestBody AskRequest request, Authentication authentication) {
        AuditContext.modelVersion("llm:" + assistant.model());
        return assistant.answer(TenantContext.get(), authentication.getName(), request.question());
    }

    /** Only a question. Any other field, a loan id included, is ignored rather than refused: there is nothing
     * a client can send that changes which loans the answer draws on. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AskRequest(@NotBlank @Size(max = 2000) String question) {
    }
}

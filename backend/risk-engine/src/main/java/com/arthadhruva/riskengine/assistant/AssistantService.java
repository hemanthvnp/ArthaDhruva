package com.arthadhruva.riskengine.assistant;

import com.arthadhruva.riskengine.score.LoanCatalogService;
import com.arthadhruva.riskengine.score.LoanFeatures;
import com.arthadhruva.riskengine.score.LoanScoreService;
import com.arthadhruva.riskengine.workflow.LoanCase;
import com.arthadhruva.riskengine.workflow.LoanCaseService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Assembles context for the analyst assistant and delegates the actual completion to {@link
 * LlmClient}. A legitimate multi-module dependency (on {@code score} and {@code workflow}) --
 * same pattern already established for {@code expectedloss} depending on {@code score}: the
 * assistant inherently needs to read what's already known about a loan, not duplicate it.
 *
 * <p>Single-turn by design for now: each call is independent, with no server-side conversation
 * history. The frontend keeps the visible back-and-forth in its own component state.
 */
@Service
public class AssistantService {

    private static final String BASE_SYSTEM_PROMPT =
            "You are a credit-risk analyst assistant embedded in the ArthaDhruva risk console. "
                    + "Answer concisely and precisely, in plain language a credit analyst would use. "
                    + "Only use the loan context given to you below; if it's insufficient to answer, say so "
                    + "rather than guessing.";

    private final LlmClient llmClient;
    private final LoanCatalogService loanCatalogService;
    private final LoanScoreService loanScoreService;
    private final LoanCaseService loanCaseService;

    public AssistantService(LlmClient llmClient, LoanCatalogService loanCatalogService,
                             LoanScoreService loanScoreService, LoanCaseService loanCaseService) {
        this.llmClient = llmClient;
        this.loanCatalogService = loanCatalogService;
        this.loanScoreService = loanScoreService;
        this.loanCaseService = loanCaseService;
    }

    public String answer(Long tenantId, String loanId, String question) {
        String systemPrompt = loanId == null || loanId.isBlank()
                ? BASE_SYSTEM_PROMPT
                : BASE_SYSTEM_PROMPT + "\n\n" + loanContext(tenantId, loanId);

        return llmClient.complete(systemPrompt, question)
                .orElse("The AI assistant isn't available right now (no model provider is configured, or it's "
                        + "temporarily unreachable) -- please try again later.");
    }

    private String loanContext(Long tenantId, String loanId) {
        StringBuilder context = new StringBuilder("Here is what's known about loan ").append(loanId).append(":\n");

        Optional.ofNullable(loanCatalogService.find(loanId)).ifPresentOrElse(
                features -> context.append("- Origination features: ").append(describeFeatures(features)).append('\n'),
                () -> context.append("- No origination features on file for this loan id.\n"));

        loanScoreService.findByTenantAndLoanId(tenantId, loanId).ifPresentOrElse(
                score -> context.append("- Most recent PD score: raw=").append(score.getRawProbability())
                        .append(", calibrated=").append(score.getCalibratedProbability())
                        .append(" (computed ").append(score.getComputedAt()).append(")\n"),
                () -> context.append("- This loan has not been scored yet.\n"));

        LoanCase loanCase = loanCaseService.getOrCreate(tenantId, loanId);
        context.append("- Case status: ").append(loanCase.getStatus())
                .append(", assigned to: ").append(loanCase.getAssignedTo() == null ? "nobody" : loanCase.getAssignedTo())
                .append(", flagged: ").append(loanCase.isFlagged()).append('\n');

        var notes = loanCaseService.notesFor(tenantId, loanId);
        if (notes.isEmpty()) {
            context.append("- No analyst notes on this loan.\n");
        } else {
            context.append("- Analyst notes (most recent first):\n");
            notes.forEach(note -> context.append("  - [").append(note.getCreatedAt()).append("] ")
                    .append(note.getAuthor()).append(": ").append(note.getText()).append('\n'));
        }

        return context.toString();
    }

    private String describeFeatures(LoanFeatures f) {
        return "creditScore=" + f.creditScore() + ", originalDti=" + f.originalDti()
                + ", originalUpb=" + f.originalUpb() + ", originalCltv=" + f.originalCltv()
                + ", originalLtv=" + f.originalLtv() + ", originalInterestRate=" + f.originalInterestRate()
                + ", occupancyStatus=" + f.occupancyStatus() + ", propertyType=" + f.propertyType()
                + ", loanPurpose=" + f.loanPurpose() + ", propertyState=" + f.propertyState();
    }
}

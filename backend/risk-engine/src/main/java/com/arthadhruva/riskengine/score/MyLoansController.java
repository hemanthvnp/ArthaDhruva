package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * A CLIENT's own-loan view: only ever returns loans linked to the calling account (via
 * User.loanIds), regardless of role -- there's nothing here another role couldn't safely see
 * about themselves, so this is open to any authenticated user (SecurityConfig's /my/** rule)
 * rather than restricted to CLIENT specifically.
 */
@RestController
public class MyLoansController {

    private final UserService userService;
    private final LoanScoreService loanScoreService;

    public MyLoansController(UserService userService, LoanScoreService loanScoreService) {
        this.userService = userService;
        this.loanScoreService = loanScoreService;
    }

    @GetMapping("/my/loans")
    public List<MyLoanView> myLoans(Authentication authentication) {
        Long tenantId = TenantContext.get();
        User user = userService.findByOrganizationAndUsername(tenantId, authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + authentication.getName()));

        return user.getLoanIds().stream()
                .map(loanId -> loanScoreService.findByTenantAndLoanId(tenantId, loanId)
                        .map(r -> new MyLoanView(loanId, r.getCalibratedProbability(), r.getComputedAt()))
                        .orElseGet(() -> new MyLoanView(loanId, null, null)))
                .toList();
    }

    /** @param calibratedProbability null if this loan hasn't been scored yet */
    public record MyLoanView(String loanId, Double calibratedProbability, Instant computedAt) {
    }
}

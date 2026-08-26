package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserRepository;
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

    private final UserRepository userRepository;
    private final LoanScoreRecordRepository loanScoreRecordRepository;

    public MyLoansController(UserRepository userRepository, LoanScoreRecordRepository loanScoreRecordRepository) {
        this.userRepository = userRepository;
        this.loanScoreRecordRepository = loanScoreRecordRepository;
    }

    @GetMapping("/my/loans")
    public List<MyLoanView> myLoans(Authentication authentication) {
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + authentication.getName()));

        return user.getLoanIds().stream()
                .map(loanId -> loanScoreRecordRepository.findById(loanId)
                        .map(r -> new MyLoanView(loanId, r.getCalibratedProbability(), r.getComputedAt()))
                        .orElseGet(() -> new MyLoanView(loanId, null, null)))
                .toList();
    }

    /** @param calibratedProbability null if this loan hasn't been scored yet */
    public record MyLoanView(String loanId, Double calibratedProbability, Instant computedAt) {
    }
}

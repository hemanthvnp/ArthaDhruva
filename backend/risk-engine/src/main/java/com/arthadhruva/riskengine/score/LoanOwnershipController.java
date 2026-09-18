package com.arthadhruva.riskengine.score;

import com.arthadhruva.riskengine.security.User;
import com.arthadhruva.riskengine.security.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Which client account(s), if any, this loan is linked to -- context an analyst reviewing a
 * loan wants ("who does this actually belong to?") that the scoring endpoints have no reason to
 * expose. ANALYST/ADMIN only (the default rule); a CLIENT has no business enumerating who else
 * owns a loan. */
@RestController
public class LoanOwnershipController {

    private final UserRepository userRepository;

    public LoanOwnershipController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/loans/{loanId}/clients")
    public List<String> clientsFor(@PathVariable String loanId) {
        return userRepository.findByLoanIdsContaining(loanId).stream().map(User::getUsername).toList();
    }
}

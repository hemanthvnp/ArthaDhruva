package com.arthadhruva.riskengine.borrower;

import com.arthadhruva.riskengine.tenant.TenantContext;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A borrower's own-loan view: only ever the loans linked to the calling account (User.loanIds), whatever the
 * role, so it is open to any signed-in ANALYST/ADMIN/CLIENT (SecurityConfig's /my/** rule). Each loan carries
 * a status band and a trend sentence instead of the probability, the open case's status without its notes,
 * and who to contact (see {@link BorrowerPortalService} for what is never included).
 */
@RestController
public class MyLoansController {

    private final BorrowerPortalService portal;

    public MyLoansController(BorrowerPortalService portal) {
        this.portal = portal;
    }

    @GetMapping("/my/loans")
    public List<BorrowerPortalService.MyLoanView> myLoans(Authentication authentication) {
        return portal.myLoans(TenantContext.get(), authentication.getName());
    }
}

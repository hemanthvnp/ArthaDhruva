package com.arthadhruva.riskengine.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public entry point for prospective customers. Banks procure through security review and a
 * pilot, not a credit card, so this records a request instead of creating an organization; a
 * PLATFORM_ADMIN provisions the organization later (see PlatformController). Public, so it sits
 * behind the anonymous per-address rate limit.
 */
@RestController
public class AccessRequestController {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestController.class);

    private final AccessRequestRepository requests;

    public AccessRequestController(AccessRequestRepository requests) {
        this.requests = requests;
    }

    public record SubmitAccessRequest(
            @NotBlank @Size(max = 100) String companyName,
            @NotBlank @Size(max = 100) String contactName,
            @NotBlank @Email @Size(max = 255) String workEmail,
            @Size(max = 100) String jobTitle,
            @Size(max = 2000) String message) {
    }

    @PostMapping("/access-requests")
    public ResponseEntity<?> submit(@Valid @RequestBody SubmitAccessRequest r) {
        AccessRequest saved = requests.save(new AccessRequest(
                r.companyName().strip(), r.contactName().strip(), r.workEmail().strip(),
                blankToNull(r.jobTitle()), blankToNull(r.message())));
        log.info("Access request {} received from {}", saved.getId(), saved.getCompanyName());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "status", "RECEIVED",
                "next", "Our team will contact you to arrange a security review and a pilot environment."));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

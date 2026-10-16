package com.arthadhruva.riskengine.borrower;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN (under /admin/**): who borrowers are pointed to when their loan has no assigned analyst. */
@RestController
public class BorrowerContactController {

    private final BorrowerContactService contacts;

    public BorrowerContactController(BorrowerContactService contacts) {
        this.contacts = contacts;
    }

    public record ContactRequest(@NotBlank @Size(max = 120) String name, @Size(max = 120) String title,
                                 @Email @Size(max = 254) String email) {
    }

    /** @param configured false when the organization has set no default contact (the other fields are then null) */
    public record ContactSetting(boolean configured, String name, String title, String email) {
    }

    @GetMapping("/admin/borrower-contact")
    public ContactSetting get() {
        return contacts.find(TenantContext.get())
                .map(c -> new ContactSetting(true, c.name(), c.title(), c.email()))
                .orElseGet(() -> new ContactSetting(false, null, null, null));
    }

    @PutMapping("/admin/borrower-contact")
    public BorrowerContactService.Contact put(@Valid @RequestBody ContactRequest request) {
        BorrowerContactService.Contact contact = new BorrowerContactService.Contact(request.name().trim(),
                blankToNull(request.title()), blankToNull(request.email()));
        contacts.save(TenantContext.get(), contact);
        return contact;
    }

    @DeleteMapping("/admin/borrower-contact")
    public ResponseEntity<Void> delete() {
        contacts.clear(TenantContext.get());
        return ResponseEntity.noContent().build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

package com.arthadhruva.riskengine.apikey;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Tenant admins issue and revoke their organization's API keys (under /admin/**). */
@RestController
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank @Size(max = 80) String name) {
    }

    @PostMapping("/admin/api-keys")
    public ResponseEntity<ApiKeyService.Created> create(@Valid @RequestBody CreateRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(TenantContext.get(), request.name(), auth.getName()));
    }

    @GetMapping("/admin/api-keys")
    public List<ApiKeyService.KeyView> list() {
        return service.list(TenantContext.get());
    }

    @DeleteMapping("/admin/api-keys/{id}")
    public ResponseEntity<?> revoke(@PathVariable long id) {
        return service.revoke(TenantContext.get(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

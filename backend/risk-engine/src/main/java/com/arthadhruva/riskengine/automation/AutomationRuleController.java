package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin-only (under /admin/**, see SecurityConfig): rules change what happens to every case. */
@RestController
public class AutomationRuleController {

    private final AutomationService service;

    public AutomationRuleController(AutomationService service) {
        this.service = service;
    }

    public record CreateRuleRequest(@NotBlank String name, @NotNull RuleTrigger trigger, @NotBlank String field,
                                    @NotNull ConditionOp op, @NotBlank String value,
                                    @NotNull List<ActionSpec> actions, Integer position) {
    }

    public record RuleView(Long id, String name, RuleTrigger trigger, String field, ConditionOp op, String value,
                           String actions, int position, boolean enabled) {
        static RuleView of(AutomationRule r) {
            return new RuleView(r.getId(), r.getName(), r.getTrigger(), r.getConditionField(), r.getConditionOp(),
                    r.getConditionValue(), r.getActions(), r.getPosition(), r.isEnabled());
        }
    }

    @GetMapping("/admin/automation-rules")
    public List<RuleView> list() {
        return service.list(TenantContext.get()).stream().map(RuleView::of).toList();
    }

    @PostMapping("/admin/automation-rules")
    public ResponseEntity<?> create(@Valid @RequestBody CreateRuleRequest r) {
        try {
            AutomationRule rule = service.create(TenantContext.get(), r.name(), r.trigger(), r.field(), r.op(),
                    r.value(), r.actions(), r.position() == null ? 0 : r.position());
            return ResponseEntity.status(HttpStatus.CREATED).body(RuleView.of(rule));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/automation-rules/{id}/enabled")
    public ResponseEntity<?> setEnabled(@PathVariable Long id, @RequestParam boolean value) {
        return service.setEnabled(TenantContext.get(), id, value)
                ? ResponseEntity.ok(Map.of("enabled", value)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/admin/automation-rules/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        return service.delete(TenantContext.get(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

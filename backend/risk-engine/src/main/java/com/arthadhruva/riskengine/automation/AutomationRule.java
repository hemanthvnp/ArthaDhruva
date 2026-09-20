package com.arthadhruva.riskengine.automation;

import com.arthadhruva.riskengine.tenant.TenantAware;
import jakarta.persistence.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.Instant;

/** "When {@code trigger} happens and {@code conditionField conditionOp conditionValue}, run
 * {@code actions}" -- tenant-scoped, ordered by {@code position} within a trigger. */
@Entity
@Table(name = "automation_rule")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class AutomationRule implements TenantAware {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_event", nullable = false)
    private RuleTrigger trigger;

    @Column(name = "condition_field", nullable = false)
    private String conditionField;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_op", nullable = false)
    private ConditionOp conditionOp;

    @Column(name = "condition_value", nullable = false)
    private String conditionValue;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String actions;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    protected AutomationRule() {
    }

    public AutomationRule(Long tenantId, String name, RuleTrigger trigger, String conditionField,
                          ConditionOp conditionOp, String conditionValue, String actions, int position) {
        this.tenantId = tenantId;
        this.name = name;
        this.trigger = trigger;
        this.conditionField = conditionField;
        this.conditionOp = conditionOp;
        this.conditionValue = conditionValue;
        this.actions = actions;
        this.position = position;
    }

    public Long getId() { return id; }
    @Override public Long getTenantId() { return tenantId; }
    public String getName() { return name; }
    public RuleTrigger getTrigger() { return trigger; }
    public String getConditionField() { return conditionField; }
    public ConditionOp getConditionOp() { return conditionOp; }
    public String getConditionValue() { return conditionValue; }
    public String getActions() { return actions; }
    public int getPosition() { return position; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}

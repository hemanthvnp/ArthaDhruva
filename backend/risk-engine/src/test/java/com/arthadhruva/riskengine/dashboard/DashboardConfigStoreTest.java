package com.arthadhruva.riskengine.dashboard;

import com.arthadhruva.riskengine.AbstractIntegrationTest;
import com.arthadhruva.riskengine.tenant.Organization;
import com.arthadhruva.riskengine.tenant.OrganizationService;
import com.arthadhruva.riskengine.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Write-behind against the real database and cache: a save is readable at once and in the table after a flush. */
class DashboardConfigStoreTest extends AbstractIntegrationTest {

    @Autowired DashboardConfigStore store;
    @Autowired OrganizationService organizations;
    @Autowired JdbcTemplate jdbc;

    private <T> T inTenant(Organization org, Supplier<T> work) {
        TenantContext.set(org.getId());
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private List<String> stored(Organization org, String username) {
        return inTenant(org, () -> jdbc.queryForList("SELECT widgets FROM dashboard_config WHERE tenant_id = ? AND username = ?",
                String.class, org.getId(), username));
    }

    @Test
    void aSaveIsReadableAtOnceAndOnlyTheLastOneOfABurstIsWritten() {
        Organization org = organizations.create("dash-" + UUID.randomUUID().toString().substring(0, 8), "Dash Bank", false, null, null);
        String user = "dee";
        store.flush();   // whatever an earlier test left buffered is not this test's business

        store.save(org.getId(), user, "[\"PORTFOLIO_KPI\"]");
        store.save(org.getId(), user, "[\"RECENT_NOTES\"]");
        store.save(org.getId(), user, "[\"RECENT_NOTES\",\"PORTFOLIO_KPI\"]");
        assertEquals(Optional.of("[\"RECENT_NOTES\",\"PORTFOLIO_KPI\"]"), inTenant(org, () -> store.find(org.getId(), user)));

        store.flush();
        assertEquals(List.of("[\"RECENT_NOTES\",\"PORTFOLIO_KPI\"]"), stored(org, user));
        assertEquals(Optional.of("[\"RECENT_NOTES\",\"PORTFOLIO_KPI\"]"), inTenant(org, () -> store.find(org.getId(), user)),
                "read from the cache or the table once the buffer is empty");
    }

    @Test
    void aSaveOlderThanTheStoredRowDoesNotReplaceIt() {
        Organization org = organizations.create("dash-" + UUID.randomUUID().toString().substring(0, 8), "Dash Bank", false, null, null);
        String user = "dov";
        store.save(org.getId(), user, "[\"MY_OPEN_CASES\"]");
        // Another replica wrote a later save first.
        inTenant(org, () -> jdbc.update("INSERT INTO dashboard_config (tenant_id, username, widgets, updated_at) "
                + "VALUES (?, ?, '[\"FLAGGED_CASES\"]', now() + interval '1 minute')", org.getId(), user));

        store.flush();
        assertEquals(List.of("[\"FLAGGED_CASES\"]"), stored(org, user));
        store.flush();
        assertTrue(stored(org, user).contains("[\"FLAGGED_CASES\"]"), "and it is not retried forever: the buffer was emptied");
    }
}

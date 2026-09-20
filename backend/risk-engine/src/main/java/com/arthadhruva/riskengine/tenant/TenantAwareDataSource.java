package com.arthadhruva.riskengine.tenant;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Decorator (structural pattern) over the pooled DataSource that tags every checked-out
 * connection with the current tenant, {@code SET app.tenant_id}, which the Postgres row-level
 * security policies (V11) key off. Set on <em>every</em> checkout, including to '' when there is
 * no tenant, so a pooled connection can never carry the previous request's tenant into the next
 * one; an untagged connection sees zero tenant rows at the database level.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return tag(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return tag(super.getConnection(username, password));
    }

    private Connection tag(Connection connection) throws SQLException {
        String tenant = TenantContext.getOptional().map(String::valueOf).orElse("");
        try (PreparedStatement ps = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            ps.setString(1, tenant);
            ps.execute();
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return connection;
    }
}

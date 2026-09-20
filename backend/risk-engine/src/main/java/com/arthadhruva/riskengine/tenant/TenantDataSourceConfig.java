package com.arthadhruva.riskengine.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class TenantDataSourceConfig {

    /** static: BeanPostProcessors must be instantiable before the rest of the config class. */
    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && "dataSource".equals(beanName) && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }
}

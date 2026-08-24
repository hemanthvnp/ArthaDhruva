package com.arthadhruva.riskengine.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelInvocationEventRepository extends JpaRepository<ModelInvocationEvent, Long> {
}

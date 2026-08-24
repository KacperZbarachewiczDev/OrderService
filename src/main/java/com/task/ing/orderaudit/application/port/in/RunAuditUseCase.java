package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.domain.audit.AuditRun;
import com.task.ing.orderaudit.domain.audit.AuditTrigger;

import java.util.Optional;

public interface RunAuditUseCase {

    Optional<AuditRun> run(AuditTrigger trigger);
}

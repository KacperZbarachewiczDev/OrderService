package com.task.ing.orderaudit.application.port.in;

import com.task.ing.orderaudit.domain.ingest.IngestOutcome;

public interface IngestEventUseCase {

    IngestOutcome ingestOrderEvent(OrderEventCommand command);

    IngestOutcome ingestPaymentEvent(PaymentEventCommand command);
}

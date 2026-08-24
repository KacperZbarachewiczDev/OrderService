package com.task.ing.orderaudit.application.port.out;

import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.EventSource;
import com.task.ing.orderaudit.domain.model.IngestedEvent;

import java.util.List;

public interface EventStorePort {

    boolean append(IngestedEvent event);

    List<EventRef> findEventRefs(String orderId, EventSource source);
}

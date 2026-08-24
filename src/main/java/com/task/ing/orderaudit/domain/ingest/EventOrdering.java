package com.task.ing.orderaudit.domain.ingest;

import com.task.ing.orderaudit.domain.model.EventRef;

public final class EventOrdering {

    private EventOrdering() {
    }

    public static boolean shouldApply(EventRef incoming, EventRef lastApplied) {
        if (incoming == null) {
            return false;
        }
        if (lastApplied == null) {
            return true;
        }
        return incoming.compareTo(lastApplied) > 0;
    }
}

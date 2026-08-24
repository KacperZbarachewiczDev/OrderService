package com.task.ing.orderaudit.integration;

import com.task.ing.orderaudit.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class ContextAndSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void the_application_starts_against_a_real_database_and_broker() {
        assertThat(context.getBeanDefinitionCount()).isPositive();
    }

    @Test
    void every_table_the_service_relies_on_exists() {
        assertThat(tableNames()).contains(
                "orders", "order_lines", "payments", "ingested_events",
                "audit_runs", "audit_issues", "audit_discrepancies",
                "resync_jobs", "notification_outbox", "scheduler_locks");
    }

    @Test
    void the_event_archive_refuses_the_same_event_twice() {
        jdbcTemplate.update("""
                insert into ingested_events
                    (event_id, source, order_id, aggregate_id, event_type, occurred_at,
                     sequence_no, payload, received_at, origin)
                values ('EVT-1', 'ORDER', 'ORD-1', 'ORD-1', 'ORDER_CREATED', now(),
                        null, cast('{}' as jsonb), now(), 'KAFKA')
                """);

        int inserted = jdbcTemplate.update("""
                insert into ingested_events
                    (event_id, source, order_id, aggregate_id, event_type, occurred_at,
                     sequence_no, payload, received_at, origin)
                values ('EVT-1', 'ORDER', 'ORD-1', 'ORD-1', 'ORDER_CREATED', now(),
                        null, cast('{}' as jsonb), now(), 'KAFKA')
                on conflict (source, event_id) do nothing
                """);

        assertThat(inserted).isZero();
        assertThat(count("ingested_events")).isEqualTo(1);
    }

    @Test
    void the_same_event_id_may_appear_once_per_source_system() {
        jdbcTemplate.update("""
                insert into ingested_events
                    (event_id, source, order_id, aggregate_id, event_type, occurred_at,
                     sequence_no, payload, received_at, origin)
                values ('EVT-1', 'ORDER', 'ORD-1', 'ORD-1', 'ORDER_CREATED', now(),
                        null, cast('{}' as jsonb), now(), 'KAFKA'),
                       ('EVT-1', 'PAYMENT', 'ORD-1', 'PAY-1', 'PAYMENT_PAID', now(),
                        null, cast('{}' as jsonb), now(), 'KAFKA')
                """);

        assertThat(count("ingested_events")).isEqualTo(2);
    }

    private java.util.List<String> tableNames() {
        return jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'",
                String.class);
    }
}

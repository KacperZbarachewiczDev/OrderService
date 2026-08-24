package com.task.ing.orderaudit.domain.audit;

import com.task.ing.orderaudit.domain.model.EventRef;
import com.task.ing.orderaudit.domain.model.Money;
import com.task.ing.orderaudit.domain.model.OrderLine;
import com.task.ing.orderaudit.domain.model.OrderSnapshot;
import com.task.ing.orderaudit.domain.model.OrderStatus;
import com.task.ing.orderaudit.domain.model.PaymentSnapshot;
import com.task.ing.orderaudit.domain.model.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.task.ing.orderaudit.support.DomainFixtures.ORDER_ID;
import static com.task.ing.orderaudit.support.DomainFixtures.completedHistory;
import static com.task.ing.orderaudit.support.DomainFixtures.event;
import static com.task.ing.orderaudit.support.DomainFixtures.line;
import static com.task.ing.orderaudit.support.DomainFixtures.local;
import static com.task.ing.orderaudit.support.DomainFixtures.order;
import static com.task.ing.orderaudit.support.DomainFixtures.payment;
import static com.task.ing.orderaudit.support.DomainFixtures.sourceWithCounts;
import static com.task.ing.orderaudit.support.DomainFixtures.sourceWithEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEngineTest {

    private static final List<EventRef> FULL_HISTORY = List.of(
            event("EVT-1", "ORDER_CREATED"),
            event("EVT-2", "ORDER_CONFIRMED"),
            event("EVT-3", "ORDER_PAID"),
            event("EVT-4", "ORDER_SHIPPED"),
            event("EVT-5", "ORDER_COMPLETED"),
            event("EVT-6", "ORDER_CANCELLED"));

    private final AuditEngine engine = new AuditEngine();

    @Test
    @DisplayName("an order that matches the source in every respect produces no findings")
    void reports_nothing_when_both_sides_agree() {
        OrderSnapshot theOrder = order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00"));
        PaymentSnapshot thePayment = payment(PaymentStatus.PAID, "150.00");

        List<Discrepancy> findings = engine.compare(
                local(theOrder, thePayment, completedHistory(), List.of(event("PAY-EVT-1", "PAYMENT_COMPLETED"))),
                sourceWithCounts(theOrder, thePayment, 2, 1));

        assertThat(findings).isEmpty();
    }

    @Test
    void rejects_missing_arguments() {
        assertThatThrownBy(() -> engine.compare(null, sourceWithCounts(null, null, 0, 0)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.compare(local(null, null, List.of(), List.of()), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    @DisplayName("existence of the order")
    class OrderExistence {
        @Test
        void reports_an_order_the_source_has_and_this_service_never_received() {
            List<Discrepancy> findings = engine.compare(
                    local(null, null, List.of(), List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, "150.00"), null, 0, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_MISSING_LOCALLY);
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.CRITICAL);
            assertThat(findings.getFirst().expected()).isEqualTo(ORDER_ID);
        }

        @Test
        void reports_an_order_this_service_holds_that_the_source_no_longer_knows() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, "150.00"), null, completedHistory(), List.of()),
                    sourceWithCounts(null, null, 2, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_MISSING_IN_SOURCE);
            assertThat(findings.getFirst().actual()).isEqualTo(ORDER_ID);
        }

        @Test
        void says_nothing_when_neither_side_has_the_order() {
            assertThat(engine.compare(
                    local(null, null, List.of(), List.of()),
                    sourceWithCounts(null, null, 0, 0)))
                    .isEmpty();
        }

        @Test
        void does_not_pile_field_findings_on_top_of_a_missing_order() {
            List<Discrepancy> findings = engine.compare(
                    local(null, null, List.of(), List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")), null, 0, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_MISSING_LOCALLY);
        }
    }

    @Nested
    @DisplayName("business fields of the order")
    class OrderFields {
        @Test
        void reports_a_status_that_drifted() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00"), order(OrderStatus.PAID, "150.00"));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_STATUS_MISMATCH);
                assertThat(finding.expected()).isEqualTo("COMPLETED");
                assertThat(finding.actual()).isEqualTo("PAID");
            });
        }

        @Test
        void reports_an_amount_that_drifted() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00"), order(OrderStatus.COMPLETED, "149.99"));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_AMOUNT_MISMATCH);
                assertThat(finding.expected()).isEqualTo("150.00 PLN");
                assertThat(finding.actual()).isEqualTo("149.99 PLN");
            });
        }

        @Test
        void does_not_confuse_formatting_with_a_business_difference() {
            OrderSnapshot fromSource = new OrderSnapshot(
                    ORDER_ID, "CUST-1", OrderStatus.COMPLETED, Money.of("150.0000", "PLN"), List.of(), null);
            OrderSnapshot held = new OrderSnapshot(
                    ORDER_ID, "CUST-1", OrderStatus.COMPLETED, Money.of("150", "pln"), List.of(), null);

            assertThat(compareOrders(fromSource, held)).isEmpty();
        }

        @Test
        void reports_a_currency_swap_and_the_amount_difference_it_implies() {
            OrderSnapshot fromSource = new OrderSnapshot(
                    ORDER_ID, "CUST-1", OrderStatus.COMPLETED, Money.of("150.00", "EUR"), List.of(), null);
            OrderSnapshot held = new OrderSnapshot(
                    ORDER_ID, "CUST-1", OrderStatus.COMPLETED, Money.of("640.00", "PLN"), List.of(), null);

            assertThat(compareOrders(fromSource, held)).extracting(Discrepancy::type)
                    .containsExactly(
                            DiscrepancyType.ORDER_CURRENCY_MISMATCH, DiscrepancyType.ORDER_AMOUNT_MISMATCH);
        }

        @Test
        void reports_a_total_the_source_has_and_this_service_lacks() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00"), order(OrderStatus.COMPLETED, null));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_AMOUNT_MISMATCH);
            assertThat(findings.getFirst().actual()).isNull();
        }

        @Test
        void reports_a_total_this_service_invented() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, null), order(OrderStatus.COMPLETED, "150.00"));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_AMOUNT_MISMATCH);
            assertThat(findings.getFirst().expected()).isNull();
        }

        @Test
        void says_nothing_when_neither_side_states_a_total() {
            assertThat(compareOrders(order(OrderStatus.COMPLETED, null), order(OrderStatus.COMPLETED, null)))
                    .isEmpty();
        }

        @Test
        void reports_an_order_attributed_to_the_wrong_customer() {
            OrderSnapshot fromSource = new OrderSnapshot(
                    ORDER_ID, "CUST-1", OrderStatus.COMPLETED, null, List.of(), null);
            OrderSnapshot held = new OrderSnapshot(
                    ORDER_ID, "CUST-2", OrderStatus.COMPLETED, null, List.of(), null);

            assertThat(compareOrders(fromSource, held)).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_CUSTOMER_MISMATCH);
        }

        @Test
        void makes_no_claim_about_a_customer_the_source_did_not_report() {
            OrderSnapshot fromSource = new OrderSnapshot(
                    ORDER_ID, null, OrderStatus.COMPLETED, null, List.of(), null);
            OrderSnapshot held = new OrderSnapshot(
                    ORDER_ID, "CUST-2", OrderStatus.COMPLETED, null, List.of(), null);

            assertThat(compareOrders(fromSource, held)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ordered products")
    class OrderLines {
        @Test
        void reports_a_product_this_service_never_recorded() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                    order(OrderStatus.COMPLETED, "150.00"));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_LINE_MISSING);
                assertThat(finding.field()).isEqualTo("P-1");
                assertThat(finding.expected()).isEqualTo("P-1 x2 @ 75.00 PLN");
            });
        }

        @Test
        void reports_a_product_this_service_has_and_the_source_does_not() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00"), line("P-9", 1, "5.00")));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_LINE_UNEXPECTED);
                assertThat(finding.field()).isEqualTo("P-9");
            });
        }

        @Test
        void reports_a_quantity_that_drifted() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 3, "75.00")));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_LINE_QUANTITY_MISMATCH);
                assertThat(finding.expected()).isEqualTo("2");
                assertThat(finding.actual()).isEqualTo("3");
            });
        }

        @Test
        void reports_a_unit_price_that_drifted() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "70.00")));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.ORDER_LINE_PRICE_MISMATCH);
                assertThat(finding.expected()).isEqualTo("75.00 PLN");
                assertThat(finding.actual()).isEqualTo("70.00 PLN");
            });
        }

        @Test
        void reports_quantity_and_price_of_the_same_product_independently() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 5, "70.00")));

            assertThat(findings).extracting(Discrepancy::type).containsExactly(
                    DiscrepancyType.ORDER_LINE_QUANTITY_MISMATCH, DiscrepancyType.ORDER_LINE_PRICE_MISMATCH);
        }

        @Test
        void stays_silent_when_the_source_reports_no_items_at_all() {
            List<Discrepancy> findings = compareOrders(
                    order(OrderStatus.COMPLETED, "150.00"),
                    order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")));

            assertThat(findings).isEmpty();
        }
    }

    @Nested
    @DisplayName("payments")
    class Payments {
        @Test
        void reports_a_payment_the_source_has_and_this_service_never_received() {
            List<Discrepancy> findings = comparePayments(payment(PaymentStatus.PAID, "150.00"), null);

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_MISSING_LOCALLY);
            assertThat(findings.getFirst().expected()).isEqualTo("PAY-1");
        }

        @Test
        void reports_a_payment_this_service_holds_that_the_source_does_not() {
            List<Discrepancy> findings = comparePayments(null, payment(PaymentStatus.PAID, "150.00"));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_MISSING_IN_SOURCE);
        }

        @Test
        void says_nothing_when_the_order_simply_has_no_payment_yet() {
            assertThat(comparePayments(null, null)).isEmpty();
        }

        @Test
        void reports_a_payment_recorded_under_the_wrong_identifier() {
            PaymentSnapshot fromSource = new PaymentSnapshot(
                    "PAY-1", ORDER_ID, PaymentStatus.PAID, Money.of("150.00", "PLN"), null);
            PaymentSnapshot held = new PaymentSnapshot(
                    "PAY-2", ORDER_ID, PaymentStatus.PAID, Money.of("150.00", "PLN"), null);

            assertThat(comparePayments(fromSource, held)).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_IDENTITY_MISMATCH);
        }

        @Test
        void reports_a_payment_status_that_drifted() {
            assertThat(comparePayments(
                    payment(PaymentStatus.PAID, "150.00"), payment(PaymentStatus.PENDING, "150.00")))
                    .extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_STATUS_MISMATCH);
        }

        @Test
        void reports_an_underpayment() {
            List<Discrepancy> findings = comparePayments(
                    payment(PaymentStatus.PAID, "150.00"), payment(PaymentStatus.PAID, "100.00"));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.PAYMENT_AMOUNT_MISMATCH);
                assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
            });
        }

        @Test
        void reports_an_amount_the_source_states_and_this_service_lacks() {
            PaymentSnapshot fromSource = new PaymentSnapshot(
                    "PAY-1", ORDER_ID, PaymentStatus.PAID, Money.of("150.00", "PLN"), null);
            PaymentSnapshot held = new PaymentSnapshot("PAY-1", ORDER_ID, PaymentStatus.PAID, null, null);

            assertThat(comparePayments(fromSource, held)).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_AMOUNT_MISMATCH);
        }

        @Test
        void says_nothing_when_neither_side_states_an_amount() {
            PaymentSnapshot fromSource = new PaymentSnapshot(
                    "PAY-1", ORDER_ID, PaymentStatus.PAID, null, null);
            PaymentSnapshot held = new PaymentSnapshot("PAY-1", ORDER_ID, PaymentStatus.PAID, null, null);

            assertThat(comparePayments(fromSource, held)).isEmpty();
        }

        @Test
        void reports_a_payment_settled_in_the_wrong_currency() {
            PaymentSnapshot fromSource = new PaymentSnapshot(
                    "PAY-1", ORDER_ID, PaymentStatus.PAID, Money.of("150.00", "PLN"), null);
            PaymentSnapshot held = new PaymentSnapshot(
                    "PAY-1", ORDER_ID, PaymentStatus.PAID, Money.of("150.00", "EUR"), null);

            assertThat(comparePayments(fromSource, held)).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_CURRENCY_MISMATCH);
        }
    }

    @Nested
    @DisplayName("event history")
    class EventHistory {
        @Test
        void reports_a_count_that_does_not_add_up() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.CREATED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED")), List.of()),
                    sourceWithCounts(order(OrderStatus.CREATED, null), null, 3, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_EVENT_COUNT_MISMATCH);
            assertThat(findings.getFirst().expected()).isEqualTo("3");
            assertThat(findings.getFirst().actual()).isEqualTo("1");
        }

        @Test
        void reports_a_payment_event_count_that_does_not_add_up() {
            List<Discrepancy> findings = engine.compare(
                    local(null, payment(PaymentStatus.PAID, "150.00"), List.of(), List.of()),
                    sourceWithCounts(null, payment(PaymentStatus.PAID, "150.00"), 0, 2));

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.PAYMENT_EVENT_COUNT_MISMATCH);
        }

        @Test
        void names_the_events_it_never_received_once_the_histories_are_downloaded() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED")), List.of()),
                    sourceWithEvents(order(OrderStatus.COMPLETED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED"), event("EVT-2", "ORDER_COMPLETED")),
                            List.of()));

            assertThat(findings).extracting(Discrepancy::type).contains(DiscrepancyType.MISSING_ORDER_EVENTS);
            assertThat(findings).filteredOn(f -> f.type() == DiscrepancyType.MISSING_ORDER_EVENTS)
                    .singleElement()
                    .extracting(Discrepancy::expected).isEqualTo("EVT-2");
        }

        @Test
        void names_the_payment_events_it_never_received() {
            List<Discrepancy> findings = engine.compare(
                    local(null, payment(PaymentStatus.PAID, "150.00"), List.of(), List.of()),
                    sourceWithEvents(null, payment(PaymentStatus.PAID, "150.00"),
                            List.of(), List.of(event("PAY-EVT-1", "PAYMENT_COMPLETED"))));

            assertThat(findings).extracting(Discrepancy::type)
                    .contains(DiscrepancyType.MISSING_PAYMENT_EVENTS);
        }

        @Test
        void does_not_claim_to_know_which_events_are_missing_when_it_only_has_counts() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.CREATED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED")), List.of()),
                    sourceWithCounts(order(OrderStatus.CREATED, null), null, 5, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .doesNotContain(DiscrepancyType.MISSING_ORDER_EVENTS);
        }

        @Test
        void truncates_a_very_long_list_of_missing_events() {
            List<EventRef> sourceEvents = IntStream.rangeClosed(1, 25)
                    .mapToObj(i -> event("EVT-" + i, "ORDER_UPDATED"))
                    .toList();

            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.UNKNOWN, null), null, List.of(), List.of()),
                    sourceWithEvents(order(OrderStatus.UNKNOWN, null), null, sourceEvents, List.of()));

            assertThat(findings).filteredOn(f -> f.type() == DiscrepancyType.MISSING_ORDER_EVENTS)
                    .singleElement()
                    .extracting(Discrepancy::expected)
                    .asString().endsWith("... (5 more)");
        }

        @Test
        void lists_every_missing_event_right_up_to_the_truncation_limit() {
            List<EventRef> sourceEvents = IntStream.rangeClosed(1, 20)
                    .mapToObj(i -> event("EVT-" + i, "ORDER_UPDATED"))
                    .toList();

            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.UNKNOWN, null), null, List.of(), List.of()),
                    sourceWithEvents(order(OrderStatus.UNKNOWN, null), null, sourceEvents, List.of()));

            assertThat(findings).filteredOn(f -> f.type() == DiscrepancyType.MISSING_ORDER_EVENTS)
                    .singleElement()
                    .extracting(Discrepancy::expected)
                    .asString().doesNotContain("more").contains("EVT-20");
        }

        @Test
        void trusts_the_details_flag_rather_than_the_list_it_was_handed() {
            List<EventRef> sourceEvents = List.of(event("EVT-1", "ORDER_CREATED"));
            SourceOrderView withoutDetails = new SourceOrderView(
                    ORDER_ID, Optional.of(order(OrderStatus.UNKNOWN, null)), Optional.empty(),
                    1, 0, sourceEvents, List.of(), false);

            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.UNKNOWN, null), null, List.of(), List.of()), withoutDetails);

            assertThat(findings).extracting(Discrepancy::type)
                    .containsExactly(DiscrepancyType.ORDER_EVENT_COUNT_MISMATCH);
        }

        @Test
        void says_nothing_when_the_histories_match_exactly() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, null), null, completedHistory(), List.of()),
                    sourceWithEvents(order(OrderStatus.COMPLETED, null), null, completedHistory(), List.of()));

            assertThat(findings).isEmpty();
        }
    }

    @Nested
    @DisplayName("completeness of the local history")
    class HistoryCompleteness {
        @Test
        void reports_a_completed_order_whose_creation_event_never_arrived() {
            List<EventRef> localEvents = List.of(
                    event("EVT-2", "ORDER_COMPLETED"), event("EVT-3", "ORDER_UPDATED"));

            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, null), null, localEvents, List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, null), null, 2, 0));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(DiscrepancyType.INCOMPLETE_ORDER_HISTORY);
                assertThat(finding.field()).isEqualTo("ORDER_CREATED");
                assertThat(finding.actual()).isEqualTo("not received");
            });
        }

        @Test
        void reports_every_lifecycle_event_that_is_missing() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, null), null,
                            List.of(event("EVT-9", "ORDER_UPDATED")), List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, null), null, 1, 0));

            assertThat(findings).filteredOn(f -> f.type() == DiscrepancyType.INCOMPLETE_ORDER_HISTORY)
                    .extracting(Discrepancy::field)
                    .containsExactly("ORDER_CREATED", "ORDER_COMPLETED");
        }

        @Test
        void judges_completeness_against_the_status_the_source_reports() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.CREATED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED")), List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, null), null, 1, 0));

            assertThat(findings).filteredOn(f -> f.type() == DiscrepancyType.INCOMPLETE_ORDER_HISTORY)
                    .extracting(Discrepancy::field)
                    .containsExactly("ORDER_COMPLETED");
        }

        @Test
        void falls_back_to_the_local_status_when_the_source_no_longer_has_the_order() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.COMPLETED, null), null,
                            List.of(event("EVT-1", "ORDER_CREATED")), List.of()),
                    sourceWithCounts(null, null, 1, 0));

            assertThat(findings).extracting(Discrepancy::type).contains(
                    DiscrepancyType.ORDER_MISSING_IN_SOURCE, DiscrepancyType.INCOMPLETE_ORDER_HISTORY);
        }

        @Test
        void makes_no_completeness_claim_for_an_order_it_does_not_hold() {
            List<Discrepancy> findings = engine.compare(
                    local(null, null, List.of(), List.of()),
                    sourceWithCounts(order(OrderStatus.COMPLETED, null), null, 0, 0));

            assertThat(findings).extracting(Discrepancy::type)
                    .doesNotContain(DiscrepancyType.INCOMPLETE_ORDER_HISTORY);
        }

        @Test
        void makes_no_completeness_claim_for_an_unrecognised_status() {
            List<Discrepancy> findings = engine.compare(
                    local(order(OrderStatus.UNKNOWN, null), null, List.of(), List.of()),
                    sourceWithCounts(order(OrderStatus.UNKNOWN, null), null, 0, 0));

            assertThat(findings).isEmpty();
        }
    }

    @Test
    @DisplayName("a badly broken order surfaces every problem at once")
    void reports_all_independent_problems_together() {
        List<Discrepancy> findings = engine.compare(
                local(order(OrderStatus.PAID, "100.00", line("P-1", 1, "75.00")),
                        payment(PaymentStatus.PENDING, "100.00"),
                        List.of(event("EVT-2", "ORDER_COMPLETED")),
                        List.of()),
                sourceWithCounts(order(OrderStatus.COMPLETED, "150.00", line("P-1", 2, "75.00")),
                        payment(PaymentStatus.PAID, "150.00"), 4, 2));

        assertThat(findings).extracting(Discrepancy::type).containsExactlyInAnyOrder(
                DiscrepancyType.ORDER_STATUS_MISMATCH,
                DiscrepancyType.ORDER_AMOUNT_MISMATCH,
                DiscrepancyType.ORDER_LINE_QUANTITY_MISMATCH,
                DiscrepancyType.PAYMENT_STATUS_MISMATCH,
                DiscrepancyType.PAYMENT_AMOUNT_MISMATCH,
                DiscrepancyType.ORDER_EVENT_COUNT_MISMATCH,
                DiscrepancyType.PAYMENT_EVENT_COUNT_MISMATCH,
                DiscrepancyType.INCOMPLETE_ORDER_HISTORY);
        assertThat(Discrepancy.highestSeverity(findings)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void returns_a_result_callers_cannot_tamper_with() {
        List<Discrepancy> findings = engine.compare(
                local(null, null, List.of(), List.of()),
                sourceWithCounts(order(OrderStatus.CREATED, null), null, 0, 0));

        assertThat(findings).hasSize(1);
        assertThatThrownBy(() -> findings.add(Discrepancy.of(DiscrepancyType.ORDER_STATUS_MISMATCH, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private List<Discrepancy> compareOrders(OrderSnapshot fromSource, OrderSnapshot held) {
        return engine.compare(
                new LocalOrderView(ORDER_ID, Optional.of(held), Optional.empty(), FULL_HISTORY, List.of()),
                SourceOrderView.withCountsOnly(
                        ORDER_ID, Optional.of(fromSource), Optional.empty(), FULL_HISTORY.size(), 0));
    }

    private List<Discrepancy> comparePayments(PaymentSnapshot fromSource, PaymentSnapshot held) {
        return engine.compare(
                new LocalOrderView(
                        ORDER_ID, Optional.empty(), Optional.ofNullable(held), List.of(), List.of()),
                SourceOrderView.withCountsOnly(
                        ORDER_ID, Optional.empty(), Optional.ofNullable(fromSource), 0, 0));
    }
}

package com.task.ing.orderaudit.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(IntegrationTestConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("order_audit")
                    .withUsername("order_audit")
                    .withPassword("order_audit")
                    .withStartupAttempts(3);

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:4.0.0")

            .withStartupAttempts(3);

    protected static final WireMockServer ORDER_SERVICE = new WireMockServer(options().dynamicPort());

    protected static final WireMockServer PAYMENT_SERVICE = new WireMockServer(options().dynamicPort());

    protected static final GreenMail SMTP = new GreenMail(smtpSetup());

    protected static KafkaTestPublisher kafkaPublisher;

    static {
        POSTGRES.start();
        KAFKA.start();
        ORDER_SERVICE.start();
        PAYMENT_SERVICE.start();
        SMTP.start();
        kafkaPublisher = new KafkaTestPublisher(KAFKA.getBootstrapServers());
    }

    private static ServerSetup smtpSetup() {
        ServerSetup setup = new ServerSetup(
                freePort(), "127.0.0.1", ServerSetupTest.SMTP.getProtocol());
        setup.setServerStartupTimeout(10_000);
        return setup;
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not reserve a port for the test SMTP server", e);
        }
    }

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("sources.order.base-url", ORDER_SERVICE::baseUrl);
        registry.add("sources.payment.base-url", PAYMENT_SERVICE::baseUrl);
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> SMTP.getSmtp().getPort());
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected com.task.ing.orderaudit.application.port.in.IngestEventUseCase ingestEvents;

    @Autowired
    protected com.task.ing.orderaudit.adapter.in.kafka.KafkaEventMapper kafkaEventMapper;

    protected SourceStubs orderService;
    protected SourceStubs paymentService;

    @BeforeEach
    void resetState() {
        ORDER_SERVICE.resetAll();
        PAYMENT_SERVICE.resetAll();
        if (!SMTP.isRunning()) {
            SMTP.start();
        }
        SMTP.reset();
        orderService = new SourceStubs(ORDER_SERVICE, "/orders");
        paymentService = new SourceStubs(PAYMENT_SERVICE, "/payments");
        truncateAllTables();
    }

    @AfterEach
    void reportUnmatchedRequests() {
        ORDER_SERVICE.findAllUnmatchedRequests().forEach(request ->
                System.out.println("[order-service] unmatched request: " + request.getUrl()));
        PAYMENT_SERVICE.findAllUnmatchedRequests().forEach(request ->
                System.out.println("[payment-service] unmatched request: " + request.getUrl()));
    }

    protected void truncateAllTables() {
        jdbcTemplate.execute("""
                truncate table audit_discrepancies, audit_issues, audit_runs, resync_jobs,
                    notification_outbox, ingested_events, order_lines, orders, payments, scheduler_locks
                restart identity cascade
                """);
    }

    protected void awaitAssertion(org.awaitility.core.ThrowingRunnable assertion) {
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(30))
                .pollInterval(java.time.Duration.ofMillis(100))
                .pollDelay(java.time.Duration.ZERO)

                .ignoreExceptions()
                .untilAsserted(assertion);
    }

    protected void givenReceivedOrderEvent(String eventJson) {
        ingestEvents.ingestOrderEvent(kafkaEventMapper.toOrderCommand(eventJson, java.time.Instant.now()));
    }

    protected void givenReceivedPaymentEvent(String eventJson) {
        ingestEvents.ingestPaymentEvent(kafkaEventMapper.toPaymentCommand(eventJson, java.time.Instant.now()));
    }

    protected long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0L : value;
    }
}

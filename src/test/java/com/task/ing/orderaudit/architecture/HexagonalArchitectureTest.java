package com.task.ing.orderaudit.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class HexagonalArchitectureTest {

    private static final String ROOT = "com.task.ing.orderaudit";

    private static JavaClasses productionCode;

    @BeforeAll
    static void importProductionCode() {
        productionCode = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("the domain knows nothing about the application, the adapters or the configuration")
    void the_domain_depends_on_nothing_of_ours() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(ROOT + ".application..", ROOT + ".adapter..", ROOT + ".config..")
                .because("the audit rules must be readable and testable without the rest of the service")
                .check(productionCode);
    }

    @Test
    @DisplayName("the domain contains no framework at all")
    void the_domain_is_free_of_frameworks() {
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..", "jakarta..", "org.hibernate..",
                        "tools.jackson..", "com.fasterxml..", "org.apache.kafka..")
                .because("a domain that needs a container to run is a domain nobody can reason about")
                .check(productionCode);
    }

    @Test
    @DisplayName("the application layer depends on its ports, never on the adapters behind them")
    void the_application_layer_does_not_reach_for_an_adapter() {
        noClasses().that().resideInAPackage(ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter..")
                .because("swapping an adapter must never require touching a use case")
                .check(productionCode);
    }

    @Test
    @DisplayName("inbound and outbound adapters do not know about each other")
    void the_adapters_are_independent() {
        noClasses().that().resideInAPackage(ROOT + ".adapter.in..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.out..")
                .because("a listener that talks to a repository has bypassed the whole application layer")
                .check(productionCode);

        noClasses().that().resideInAPackage(ROOT + ".adapter.out..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".adapter.in..")
                .check(productionCode);
    }

    @Test
    @DisplayName("only the outbound persistence adapter knows about JPA")
    void persistence_stays_in_the_persistence_adapter() {
        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().resideInAPackage(ROOT + ".adapter.out.persistence.entity")
                .because("an entity outside the persistence adapter is the schema leaking into the model")
                .check(productionCode);
    }

    @Test
    @DisplayName("only the inbound REST adapter serves HTTP")
    void controllers_stay_in_the_rest_adapter() {
        classes().that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage(ROOT + ".adapter.in.rest")
                .check(productionCode);
    }

    @Test
    @DisplayName("outbound ports are interfaces, so every one of them can be substituted")
    void the_outbound_ports_are_interfaces() {
        classes().that().resideInAPackage(ROOT + ".application.port.out")
                .and().haveSimpleNameEndingWith("Port")
                .should().beInterfaces()
                .check(productionCode);
    }

    @Test
    @DisplayName("the packages form no dependency cycles")
    void there_are_no_cycles() {
        SlicesRuleDefinition.slices()
                .matching(ROOT + ".(*)..")
                .should().beFreeOfCycles()
                .check(productionCode);
    }
}

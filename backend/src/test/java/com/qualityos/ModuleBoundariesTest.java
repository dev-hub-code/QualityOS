package com.qualityos;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

class ModuleBoundariesTest {

    static final List<String> MODULES = List.of(
            "identity", "organization", "supplier", "material", "product", "purchaseorder", "materialreceipt",
            "inspection", "notification", "quality", "traceability", "recall", "calibration", "auditmanagement", "reporting", "subscription");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.qualityos");

    @ParameterizedTest
    @FieldSource("MODULES")
    void repositoriesAndEntitiesArePrivateToTheirModule(String module) {
        classes().that().resideInAnyPackage(
                        "com.qualityos." + module + ".repository..", "com.qualityos." + module + ".entity..")
                .should().onlyHaveDependentClassesThat().resideInAPackage("com.qualityos." + module + "..")
                .allowEmptyShould(true)
                .check(CLASSES);
    }

    @Test
    void commonDependsOnNoBusinessModule() {
        noClasses().that().resideInAPackage("com.qualityos.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        MODULES.stream().map(m -> "com.qualityos." + m + "..").toArray(String[]::new))
                .check(CLASSES);
    }

    @Test
    void controllersDoNotTouchRepositories() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .check(CLASSES);
    }
}

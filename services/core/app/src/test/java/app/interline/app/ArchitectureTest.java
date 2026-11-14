package app.interline.app;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("app.interline");

    @Test void AC12_rulesAreNotVacuousAndDomainHasNoFrameworkOrAdapterDependencies() {
        assertThat(classes).isNotEmpty();
        assertThat(classes.stream().filter(c -> c.getPackageName().startsWith("app.interline.domain"))).isNotEmpty();
        assertThat(classes.stream().filter(c -> c.getPackageName().startsWith("app.interline.agent"))).isNotEmpty();
        noClasses().that().resideInAPackage("app.interline.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..", "app.interline.persistence..", "app.interline.agent..", "app.interline.ai..", "app.interline.app..", "app.interline.mockagent..")
                .check(classes);
    }

    @Test void AC12_onlyAgentAdapterMayCreateOrControlProcesses() {
        noClasses().that().resideOutsideOfPackage("app.interline.agent..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder").check(classes);
        noClasses().that().resideOutsideOfPackage("app.interline.agent..")
                .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessHandle").check(classes);
        assertThat(classes.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTarget().getOwner().getName().equals("java.lang.Runtime"))
                .filter(call -> call.getTarget().getName().equals("exec"))
                .filter(call -> !call.getOriginOwner().getPackageName().startsWith("app.interline.agent")))
                .as("Runtime.exec outside adapter-agent-protocol").isEmpty();
    }

    @Test void AC12_coreNeverDiscoversOsPathsOrLinksPlatformApis() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("java.awt..", "javax.swing..", "com.apple..", "com.sun.jna..", "sun.misc..", "..win32..", "..mac..")
                .check(classes);
        // A stricter rule also disallows harmless property reads. Explicit paths and config injection remove the need for either.
        assertThat(classes.stream().flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTarget().getOwner().getName().equals("java.lang.System"))
                .filter(call -> java.util.Set.of("getProperty", "getProperties", "getenv").contains(call.getTarget().getName())))
                .as("No System property or environment based OS discovery").isEmpty();
    }
}

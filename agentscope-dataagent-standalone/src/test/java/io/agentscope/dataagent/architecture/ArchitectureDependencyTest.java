package io.agentscope.dataagent.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Checks production bytecode, including qualified calls that an import-only scan would miss. */
class ArchitectureDependencyTest {
    private static final String BASE = "io.agentscope.dataagent.";
    private static final Set<String> ROOTS = Set.of("web", "dataset", "tools", "runtime");
    private static JavaClasses production;
    private static Set<Dependency> dependencies;

    @BeforeAll
    static void importProductionClasses() {
        Path classes = Path.of("target/classes/io/agentscope/dataagent");
        assertThat(classes).isDirectory();
        production = new ClassFileImporter().importPath(classes);
        assertThat(production).isNotEmpty();
        dependencies =
                production.stream()
                        .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                        .filter(d -> d.getTargetClass().getName().startsWith(BASE))
                        .collect(Collectors.toSet());
    }

    @Test
    void productionStaysWithinTheFourBusinessRoots() {
        assertThat(production.stream().map(c -> root(c.getName())).collect(Collectors.toSet()))
                .containsExactlyInAnyOrderElementsOf(ROOTS);
        assertThat(
                        production.stream()
                                .filter(c -> root(c.getName()).equals("tools"))
                                .map(JavaClass::getPackageName))
                .allMatch(p -> p.equals(BASE + "tools.data") || p.startsWith(BASE + "tools.data."));
    }

    @Test
    void businessRuntimeAndToolsDoNotDependOnHttpOrApplicationAssembly() {
        var forbidden =
                dependencies.stream()
                        .filter(d -> !root(d.getOriginClass().getName()).equals("web"))
                        .filter(d -> isHttpOrAssembly(d.getTargetClass()))
                        .map(Dependency::getDescription)
                        .sorted()
                        .toList();
        assertThat(forbidden)
                .as(
                        "Move the shared responsibility out of the HTTP/config adapter; do not add"
                                + " an exception")
                .isEmpty();
    }

    @Test
    void jpaEntitiesAndRepositoriesRemainPersistenceLeaves() {
        var forbidden =
                dependencies.stream()
                        .filter(
                                d ->
                                        d.getOriginClass()
                                                .getPackageName()
                                                .equals(BASE + "web.persistence.jpa"))
                        .filter(
                                d -> {
                                    String name = outer(d.getOriginClass().getName());
                                    return name.endsWith("Entity") || name.endsWith("Repository");
                                })
                        .filter(
                                d ->
                                        !d.getTargetClass()
                                                .getPackageName()
                                                .equals(BASE + "web.persistence.jpa"))
                        .map(Dependency::getDescription)
                        .sorted()
                        .toList();
        assertThat(forbidden)
                .as("Persistence data types must not call back into business or adapters")
                .isEmpty();
    }

    @Test
    void legacyWebDependenciesCannotGrowAndRetiredExceptionsMustBeRemoved() throws IOException {
        Set<String> current =
                dependencies.stream()
                        .filter(d -> !root(d.getOriginClass().getName()).equals("web"))
                        .filter(d -> root(d.getTargetClass().getName()).equals("web"))
                        .filter(
                                d ->
                                        !d.getTargetClass()
                                                .getPackageName()
                                                .equals(BASE + "web.persistence.jpa"))
                        .map(ArchitectureDependencyTest::pair)
                        .collect(Collectors.toCollection(TreeSet::new));
        writeDependencyReport(current);
        Path baseline = Path.of("src/test/resources/architecture/legacy-web-dependencies.tsv");
        assertThat(baseline)
                .as("A reviewed baseline is required; never generate it automatically in CI")
                .exists();
        Set<String> accepted = new TreeSet<>();
        for (String line : Files.readAllLines(baseline, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t", -1);
            assertThat(fields).as("Expected origin, target and reason: %s", line).hasSize(3);
            assertThat(fields[2]).as("Every debt entry needs a reason").isNotBlank();
            assertThat(accepted.add(fields[0] + "\t" + fields[1]))
                    .as("Duplicate debt entry: %s", line)
                    .isTrue();
        }
        Set<String> added = new TreeSet<>(current);
        added.removeAll(accepted);
        Set<String> retired = new TreeSet<>(accepted);
        retired.removeAll(current);
        assertThat(added)
                .as(
                        "New reverse dependencies; extract the shared responsibility or review the"
                                + " design")
                .isEmpty();
        assertThat(retired)
                .as("Remove these retired debt entries; do not leave blanket exceptions")
                .isEmpty();
    }

    @Test
    void mdlReadComponentsDoNotEnterPublicationOrWorkspaceWrites() {
        Set<String> readComponents =
                Set.of(
                        BASE + "dataset.MdlReadService",
                        BASE + "dataset.MdlWorkspaceReader",
                        BASE + "dataset.MdlTypes");
        Set<String> writeComponents =
                Set.of(
                        BASE + "dataset.MdlPublishService",
                        BASE + "dataset.MdlSeeder",
                        BASE + "dataset.MdlBuildChecks",
                        BASE + "dataset.WrenCli",
                        BASE + "dataset.ModelingWorkflowStore");
        var reverse =
                dependencies.stream()
                        .filter(d -> readComponents.contains(outer(d.getOriginClass().getName())))
                        .filter(d -> writeComponents.contains(outer(d.getTargetClass().getName())))
                        .map(Dependency::getDescription)
                        .toList();
        assertThat(reverse)
                .as("Read projections must not depend on publish orchestration or commands")
                .isEmpty();
        var mutations =
                production.stream()
                        .filter(c -> readComponents.contains(outer(c.getName())))
                        .flatMap(c -> c.getCodeUnitAccessesFromSelf().stream())
                        .filter(
                                call -> {
                                    String owner = call.getTargetOwner().getName();
                                    String method = call.getName();
                                    if (owner.equals(BASE + "dataset.MdlWorkspaceService"))
                                        return !method.equals("workspaceRoot");
                                    if (owner.equals("java.nio.file.Files"))
                                        return !Set.of(
                                                        "isRegularFile",
                                                        "isDirectory",
                                                        "readString",
                                                        "size",
                                                        "getLastModifiedTime",
                                                        "walk",
                                                        "list")
                                                .contains(method);
                                    return owner.endsWith("Repository")
                                            && (method.startsWith("save")
                                                    || method.startsWith("delete"));
                                })
                        .map(call -> call.getDescription())
                        .toList();
        assertThat(mutations)
                .as("Read operations must not write files, reconcile or persist metadata")
                .isEmpty();
    }

    @Test
    void mdlBuildChecksDoNotOwnPublicationStateOrLocking() {
        String name = BASE + "dataset.MdlBuildChecks";
        Set<String> allowed =
                Set.of(
                        name,
                        BASE + "dataset.WrenProperties",
                        BASE + "dataset.WrenCli",
                        BASE + "dataset.WrenProfileHome",
                        BASE + "dataset.MdlTypes",
                        BASE + "dataset.MdlWorkspaceReader",
                        BASE + "dataset.DatasetException",
                        BASE + "web.persistence.jpa.ExternalDataSourceEntity");
        var component = production.get(name);
        assertThat(component.getModifiers())
                .doesNotContain(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC);
        var forbidden =
                component.getDirectDependenciesFromSelf().stream()
                        .filter(
                                d -> {
                                    String target = d.getTargetClass().getName();
                                    return (target.startsWith(BASE)
                                                    && !allowed.contains(outer(target)))
                                            || target.startsWith("org.springframework.")
                                            || target.startsWith("java.sql.")
                                            || target.startsWith("javax.sql.")
                                            || target.startsWith("java.util.concurrent.locks.")
                                            || target.equals("java.lang.ProcessBuilder");
                                })
                        .map(Dependency::getDescription)
                        .toList();
        assertThat(forbidden)
                .as("Build checks use selected inputs, not state, repositories or locks")
                .isEmpty();
        var unexpected =
                component.getCodeUnitAccessesFromSelf().stream()
                        .filter(
                                call -> {
                                    String owner = call.getTargetOwner().getName();
                                    String method = call.getName();
                                    if (owner.equals("java.nio.file.Files"))
                                        return !Set.of("createTempFile", "deleteIfExists")
                                                .contains(method);
                                    if (owner.equals(BASE + "dataset.WrenProperties"))
                                        return !method.equals("timeout");
                                    if (owner.equals(BASE + "dataset.WrenProfileHome"))
                                        return !method.equals("connectionInfo");
                                    return false;
                                })
                        .map(call -> call.getDescription())
                        .toList();
        assertThat(unexpected)
                .as("Build checks own temporary connection IO only, not project mutations")
                .isEmpty();
    }

    private static boolean isHttpOrAssembly(JavaClass type) {
        return type.getPackageName().equals(BASE + "web.api")
                || type.getPackageName().startsWith(BASE + "web.api.")
                || type.getPackageName().equals(BASE + "web.config")
                || type.getPackageName().startsWith(BASE + "web.config.")
                || type.getName().equals(BASE + "web.DataAgentApp")
                || type.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                || type.isAnnotatedWith("org.springframework.stereotype.Controller");
    }

    private static String root(String name) {
        return name.substring(BASE.length()).split("\\.", 2)[0];
    }

    private static String outer(String name) {
        return name.split("\\$", 2)[0];
    }

    private static String pair(Dependency dependency) {
        return outer(dependency.getOriginClass().getName()).substring(BASE.length())
                + "\t"
                + outer(dependency.getTargetClass().getName()).substring(BASE.length());
    }

    private static void writeDependencyReport(Set<String> current) throws IOException {
        Path directory = Path.of("target/architecture");
        Files.createDirectories(directory);
        Files.write(
                directory.resolve("legacy-web-dependencies.tsv"), current, StandardCharsets.UTF_8);
        var all =
                dependencies.stream()
                        .map(ArchitectureDependencyTest::pair)
                        .collect(Collectors.toCollection(TreeSet::new));
        Files.write(directory.resolve("class-dependencies.tsv"), all, StandardCharsets.UTF_8);
        Map<String, Integer> counts = new TreeMap<>();
        for (String pair : all) {
            String[] names = pair.split("\t");
            String from = names[0].split("\\.", 2)[0];
            String to = names[1].split("\\.", 2)[0];
            if (!from.equals(to)) counts.merge(from + " -> " + to, 1, Integer::sum);
        }
        List<String> report =
                counts.entrySet().stream().map(e -> e.getKey() + "\t" + e.getValue()).toList();
        Files.write(directory.resolve("root-dependencies.tsv"), report, StandardCharsets.UTF_8);
    }
}

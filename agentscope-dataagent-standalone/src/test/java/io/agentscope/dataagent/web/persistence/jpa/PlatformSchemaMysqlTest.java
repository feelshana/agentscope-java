package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Read-only schema validation against an explicitly selected disposable MySQL database. */
@Tag("external")
@EnabledIfEnvironmentVariable(named = "DATAAGENT_SCHEMA_JDBC", matches = "jdbc:mysql:.+")
class PlatformSchemaMysqlTest {
    @Test
    void allJpaMappingsValidateAgainstTheInstalledSchema() throws Exception {
        var source =
                new DriverManagerDataSource(
                        System.getenv("DATAAGENT_SCHEMA_JDBC"),
                        System.getenv("DATAAGENT_SCHEMA_USER"),
                        System.getenv("DATAAGENT_SCHEMA_PASSWORD"));
        try (var connection = source.getConnection()) {
            assertThat(connection.getCatalog()).startsWith("dataagent_schema_verify_");
        }
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("io.agentscope.dataagent.web.persistence.jpa");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(
                Map.of(
                        "hibernate.hbm2ddl.auto",
                        "validate",
                        "hibernate.physical_naming_strategy",
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        try {
            factory.afterPropertiesSet();
            var entities = factory.getObject().getMetamodel().getEntities();
            assertThat(entities).hasSize(35);
            try (var connection = source.getConnection()) {
                for (var entity : entities) {
                    var type = entity.getJavaType();
                    var table = type.getAnnotation(jakarta.persistence.Table.class);
                    var indexes = new java.util.HashMap<String, java.util.List<String>>();
                    var unique = new java.util.HashSet<String>();
                    try (var rows =
                            connection
                                    .getMetaData()
                                    .getIndexInfo(
                                            connection.getCatalog(),
                                            null,
                                            table.name(),
                                            false,
                                            false)) {
                        while (rows.next()) {
                            String name = rows.getString("INDEX_NAME"),
                                    column = rows.getString("COLUMN_NAME");
                            if (name == null || column == null) continue;
                            indexes.computeIfAbsent(name, ignored -> new java.util.ArrayList<>())
                                    .add(column);
                            if (!rows.getBoolean("NON_UNIQUE")) unique.add(name);
                        }
                    }
                    for (var index : table.indexes()) {
                        assertThat(indexes.get(index.name()))
                                .as(table.name() + "." + index.name())
                                .containsExactly(index.columnList().replace(" ", "").split(","));
                        if (index.unique()) assertThat(unique).contains(index.name());
                    }
                    var uniqueColumns = unique.stream().map(indexes::get).toList();
                    for (var constraint : table.uniqueConstraints())
                        assertThat(uniqueColumns)
                                .as(table.name() + " unique constraint")
                                .contains(java.util.List.of(constraint.columnNames()));
                    var idColumns = new java.util.ArrayList<String>();
                    for (var field : type.getDeclaredFields()) {
                        if (!field.isAnnotationPresent(jakarta.persistence.Id.class)) continue;
                        var column = field.getAnnotation(jakarta.persistence.Column.class);
                        idColumns.add(
                                column == null || column.name().isEmpty()
                                        ? field.getName()
                                                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                                                .toLowerCase(java.util.Locale.ROOT)
                                        : column.name());
                    }
                    assertThat(indexes.get("PRIMARY"))
                            .as(table.name() + " primary key")
                            .containsExactlyInAnyOrderElementsOf(idColumns);
                    for (var field : type.getDeclaredFields()) {
                        var column = field.getAnnotation(jakarta.persistence.Column.class);
                        if (column != null && !column.nullable()) {
                            String fieldName =
                                    column.name().isEmpty()
                                            ? field.getName()
                                                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                                                    .toLowerCase(java.util.Locale.ROOT)
                                            : column.name();
                            try (var rows =
                                    connection
                                            .getMetaData()
                                            .getColumns(
                                                    connection.getCatalog(),
                                                    null,
                                                    table.name(),
                                                    fieldName)) {
                                assertThat(rows.next()).as(table.name() + "." + fieldName).isTrue();
                                assertThat(rows.getString("IS_NULLABLE"))
                                        .as(table.name() + "." + fieldName + " not null")
                                        .isEqualTo("NO");
                            }
                        }
                        if (column != null && column.unique()) {
                            String name =
                                    column == null || column.name().isEmpty()
                                            ? field.getName()
                                                    .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                                                    .toLowerCase(java.util.Locale.ROOT)
                                            : column.name();
                            assertThat(uniqueColumns)
                                    .as(table.name() + "." + name + " unique")
                                    .contains(java.util.List.of(name));
                        }
                    }
                }
            }
        } finally {
            factory.destroy();
        }
    }
}

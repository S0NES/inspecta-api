package br.com.vistoria.core.modules.metrics;

import br.com.vistoria.core.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V22 metrics schema - migration (Testcontainers, skipped sem Docker)")
class MetricsMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway roda V22 e cria o schema metrics com a tabela envios")
    void v22_criaSchemaMetrics() {
        Integer schema = jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'metrics'",
                Integer.class);
        assertThat(schema).isEqualTo(1);

        Integer tabela = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'metrics' and table_name = 'envios'",
                Integer.class);
        assertThat(tabela).isEqualTo(1);

        // tabela consultável (mesmo vazia)
        Integer linhas = jdbc.queryForObject("select count(*) from metrics.envios", Integer.class);
        assertThat(linhas).isNotNull();
    }
}

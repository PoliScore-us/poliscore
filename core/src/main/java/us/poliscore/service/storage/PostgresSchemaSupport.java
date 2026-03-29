package us.poliscore.service.storage;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class PostgresSchemaSupport {

	@ConfigProperty(name = "quarkus.hibernate-orm.database.default-schema", defaultValue = "blue")
	Optional<String> configuredSchema;

	public Optional<String> schema() {
		return configuredSchema
				.map(String::trim)
				.filter(StringUtils::isNotBlank);
	}

	public String qualifyTable(String tableName) {
		validateIdentifier(tableName);
		return schema()
				.map(schemaName -> quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName))
				.orElseGet(() -> quoteIdentifier(tableName));
	}

	private void validateIdentifier(String identifier) {
		if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
			throw new IllegalArgumentException("Unsupported postgres identifier: " + identifier);
		}
	}

	private String quoteIdentifier(String identifier) {
		validateIdentifier(identifier);
		return "\"" + identifier + "\"";
	}
}

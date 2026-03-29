package us.poliscore.service.storage.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.Session;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.SneakyThrows;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;
import us.poliscore.service.storage.PostgresSchemaSupport;
import us.poliscore.service.storage.PaginatedList;

public abstract class AbstractPostgresEntityRepository<T extends Persistable> {

	protected static final String CURSOR_DELIMITER = "~`~";
	protected static final int DEFAULT_BATCH_SIZE = 1000;
	private static final ObjectMapper MAPPER = PoliscoreUtil.getObjectMapper();

	@Inject
	Instance<EntityManager> entityManagerInstance;

	@Inject
	PostgresSchemaSupport schemaSupport;

	protected abstract Class<T> entityClass();

	protected abstract String tableName();

	protected String qualifiedTableName() {
		return schemaSupport.qualifyTable(tableName());
	}

	protected EntityManager requireEntityManager() {
		return entityManagerInstance.get();
	}

	protected void executeBatch(String sql, List<T> entities, SqlBinder<T> binder) {
		executeBatch(sql, entities, DEFAULT_BATCH_SIZE, binder);
	}

	protected void executeBatch(String sql, List<T> entities, int batchSize, SqlBinder<T> binder) {
		if (entities == null || entities.isEmpty()) {
			return;
		}

		requireEntityManager().unwrap(Session.class).doWork(connection -> {
			try (PreparedStatement stmt = connection.prepareStatement(sql)) {
				int index = 0;
				for (T entity : entities) {
					try {
						binder.bind(stmt, entity);
					} catch (Exception e) {
						throw new SQLException("Unable to bind postgres upsert values for " + entityClass().getSimpleName(), e);
					}
					stmt.addBatch();
					if (++index % batchSize == 0) {
						stmt.executeBatch();
					}
				}

				if (index % batchSize != 0) {
					stmt.executeBatch();
				}
			}
		});
	}

	protected String toJson(Object value) {
		if (value == null) {
			return null;
		}

		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("Unable to serialize json payload for postgres", e);
		}
	}

	protected void setJson(PreparedStatement stmt, int index, Object value) throws SQLException {
		stmt.setString(index, toJson(value));
	}

	@Transactional
	public void put(T entity) {
		requireEntityManager().merge(entity);
	}

	@Transactional
	public void putIfLatest(T entity) {
		T existing = requireEntityManager().find(entityClass(), entity.getId());
		var existingLastUpdate = (java.time.temporal.Temporal) readProperty(existing, "lastUpdate");
		var incomingLastUpdate = (java.time.temporal.Temporal) readProperty(entity, "lastUpdate");
		if (existing != null && existingLastUpdate instanceof java.time.LocalDateTime existingTime
				&& incomingLastUpdate instanceof java.time.LocalDateTime incomingTime
				&& existingTime.isAfter(incomingTime)) {
			return;
		}

		requireEntityManager().merge(entity);
	}

	public Optional<T> get(String id) {
		return Optional.ofNullable(requireEntityManager().find(entityClass(), id));
	}

	public boolean exists(String id) {
		return get(id).isPresent();
	}

	@Transactional
	public long count() {
		return requireEntityManager()
				.createQuery("select count(e) from " + entityClass().getSimpleName() + " e", Long.class)
				.getSingleResult();
	}

	public List<T> query() {
		return query(-1, null, null, null, null, Persistable.getClassStorageBucket(entityClass(), null));
	}

	public PaginatedList<T> query(String datasetKey, int pageSize, String index, Boolean ascending, String startKey, String sortKey) {
		return query(pageSize, index, ascending, startKey, sortKey, Persistable.getClassStorageBucket(entityClass(), datasetKey));
	}

	@SuppressWarnings("unchecked")
	public PaginatedList<T> query(int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		String resolvedIndex = StringUtils.defaultIfBlank(index, Persistable.OBJECT_BY_DATE_INDEX);
		boolean resolvedAscending = ascending == null || ascending;
		String column = columnForIndex(resolvedIndex);
		boolean numericColumn = isNumericColumn(column);
		Cursor cursor = Cursor.parse(startKey, numericColumn);
		List<Object> params = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedTableName()).append(" WHERE storage_bucket = ?");
		params.add(storageBucket);

		if (sortKey != null) {
			if (numericColumn) {
				sql.append(" AND ").append(column).append(" = ?");
				params.add(Long.valueOf(sortKey));
			} else {
				sql.append(" AND ").append(column).append(" LIKE ?");
				params.add(sortKey + "%");
			}
		}

		if (cursor != null) {
			sql.append(" AND (");
			sql.append(column).append(resolvedAscending ? " > ?" : " < ?");
			sql.append(" OR (").append(column).append(" = ? AND id ").append(resolvedAscending ? ">" : "<").append(" ?))");
			params.add(cursor.value);
			params.add(cursor.value);
			params.add(cursor.id);
		}

		String orderDirection = resolvedAscending ? "ASC" : "DESC";
		sql.append(" ORDER BY ").append(column).append(" ").append(orderDirection).append(" NULLS LAST, id ").append(orderDirection);
		if (pageSize > 0) {
			sql.append(" LIMIT ?");
			params.add(pageSize);
		}

		Query query = requireEntityManager().createNativeQuery(sql.toString(), entityClass());
		for (int i = 0; i < params.size(); i++) {
			query.setParameter(i + 1, params.get(i));
		}

		List<T> rows = query.getResultList();
		String lastEvaluatedKey = null;
		for (T item : rows) {
			Object lastValue = readProperty(item, propertyForIndex(resolvedIndex));
			lastEvaluatedKey = lastValue == null ? item.getId() : item.getId() + CURSOR_DELIMITER + String.valueOf(lastValue);
		}

		return new PaginatedList<>(rows, pageSize, startKey, lastEvaluatedKey);
	}

	protected String columnForIndex(String index) {
		if (Persistable.OBJECT_BY_DATE_INDEX.equals(index)) {
			return "date_value";
		} else if (Persistable.OBJECT_BY_RATING_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_RATING_INDEX.equals(index)) {
			return "rating_value";
		} else if (Persistable.OBJECT_BY_RATING_ABS_INDEX.equals(index)) {
			return "rating_abs_value";
		} else if (Persistable.OBJECT_BY_LOCATION_INDEX.equals(index)) {
			return "location_value";
		} else if (Persistable.OBJECT_BY_IMPACT_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX.equals(index)) {
			return "impact_value";
		} else if (Persistable.OBJECT_BY_IMPACT_ABS_INDEX.equals(index)) {
			return "impact_abs_value";
		} else if (Persistable.OBJECT_BY_HOT_INDEX.equals(index)) {
			return "hot_value";
		}

		throw new UnsupportedOperationException("Unsupported Postgres index " + index);
	}

	private String propertyForIndex(String index) {
		if (Persistable.OBJECT_BY_DATE_INDEX.equals(index)) {
			return "dateValue";
		} else if (Persistable.OBJECT_BY_RATING_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_RATING_INDEX.equals(index)) {
			return "ratingValue";
		} else if (Persistable.OBJECT_BY_RATING_ABS_INDEX.equals(index)) {
			return "ratingAbsValue";
		} else if (Persistable.OBJECT_BY_LOCATION_INDEX.equals(index)) {
			return "locationValue";
		} else if (Persistable.OBJECT_BY_IMPACT_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX.equals(index)) {
			return "impactValue";
		} else if (Persistable.OBJECT_BY_IMPACT_ABS_INDEX.equals(index)) {
			return "impactAbsValue";
		} else if (Persistable.OBJECT_BY_HOT_INDEX.equals(index)) {
			return "hotValue";
		}

		throw new UnsupportedOperationException("Unsupported Postgres index " + index);
	}

	private boolean isNumericColumn(String column) {
		return column.endsWith("_value") && !column.equals("date_value") && !column.equals("location_value");
	}

	@SneakyThrows
	private Object readProperty(Object target, String name) {
		String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);

		for (String prefix : new String[] { "get", "is" }) {
			try {
				Method method = target.getClass().getMethod(prefix + suffix);
				method.setAccessible(true);
				return method.invoke(target);
			} catch (NoSuchMethodException ignore) {
			}
		}

		Class<?> current = target.getClass();
		while (current != null && current != Object.class) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			} catch (NoSuchFieldException ignore) {
				current = current.getSuperclass();
			}
		}

		return null;
	}

	protected static final class Cursor {
		private final String id;
		private final Object value;

		private Cursor(String id, Object value) {
			this.id = id;
			this.value = value;
		}

		protected static Cursor parse(String raw, boolean numericColumn) {
			if (StringUtils.isBlank(raw)) {
				return null;
			}

			String[] parts = raw.split(CURSOR_DELIMITER, 2);
			if (parts.length == 1) {
				return null;
			}

			Object value = numericColumn ? Long.valueOf(parts[1]) : parts[1];
			return new Cursor(parts[0], value);
		}

		protected String id() {
			return id;
		}

		protected Object value() {
			return value;
		}
	}

	@FunctionalInterface
	protected interface SqlBinder<E> {
		void bind(PreparedStatement stmt, E entity) throws Exception;
	}
}

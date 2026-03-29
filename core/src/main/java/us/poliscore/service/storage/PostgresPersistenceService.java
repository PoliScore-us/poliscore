package us.poliscore.service.storage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.SneakyThrows;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import us.poliscore.PoliscoreUtil;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.session.SessionInterpretation;

@ApplicationScoped
public class PostgresPersistenceService implements ObjectStorageServiceIF
{
	private static final String CURSOR_DELIMITER = "~`~";
	private static final DateTimeFormatter OFFSET_DATE_TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	
	@Inject
	Instance<DataSource> dataSourceInstance;

	private final Set<String> initializedTables = ConcurrentHashMap.newKeySet();
	private final ObjectMapper mapper = PoliscoreUtil.getObjectMapper();

	private DataSource requireDataSource()
	{
		if (!dataSourceInstance.isResolvable()) {
			throw new IllegalStateException("Postgres storage is enabled but no DataSource bean is available.");
		}
		return dataSourceInstance.get();
	}

	public boolean isEnabled()
	{
		return dataSourceInstance.isResolvable();
	}

	private void rejectEntityBackedClass(Class<?> clazz)
	{
		if (Bill.class.equals(clazz) || Legislator.class.equals(clazz) || SessionInterpretation.class.equals(clazz)) {
			throw new UnsupportedOperationException(clazz.getSimpleName() + " is persisted through its JPA repository, not the generic PostgresPersistenceService.");
		}
	}

	private synchronized void ensureTable(Class<?> clazz)
	{
		final String tableName = getTableName(clazz);
		if (initializedTables.contains(tableName)) {
			return;
		}

		try (Connection conn = requireDataSource().getConnection();
		     Statement stmt = conn.createStatement()) {
			stmt.executeUpdate(
					"CREATE TABLE IF NOT EXISTS " + tableName + " (" +
					"id TEXT PRIMARY KEY, " +
					"storage_bucket TEXT NOT NULL, " +
					"payload_json TEXT NOT NULL, " +
					"date_value TEXT NULL, " +
					"rating_value BIGINT NULL, " +
					"rating_abs_value BIGINT NULL, " +
					"location_value TEXT NULL, " +
					"impact_value BIGINT NULL, " +
					"impact_abs_value BIGINT NULL, " +
					"hot_value BIGINT NULL, " +
					"last_update_value TEXT NULL, " +
					"updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()" +
					")");

			createIndex(stmt, tableName, "bucket", "storage_bucket");
			createIndex(stmt, tableName, "date", "storage_bucket, date_value, id");
			createIndex(stmt, tableName, "rating", "storage_bucket, rating_value, id");
			createIndex(stmt, tableName, "rating_abs", "storage_bucket, rating_abs_value, id");
			createIndex(stmt, tableName, "location", "storage_bucket, location_value, id");
			createIndex(stmt, tableName, "impact", "storage_bucket, impact_value, id");
			createIndex(stmt, tableName, "impact_abs", "storage_bucket, impact_abs_value, id");
			createIndex(stmt, tableName, "hot", "storage_bucket, hot_value, id");
			createIndex(stmt, tableName, "last_update", "last_update_value");
		}
		catch (SQLException e) {
			throw new RuntimeException("Unable to initialize Postgres storage table " + tableName, e);
		}

		initializedTables.add(tableName);
	}

	private void createIndex(Statement stmt, String tableName, String suffix, String columns) throws SQLException
	{
		stmt.executeUpdate("CREATE INDEX IF NOT EXISTS " + tableName + "_" + suffix + "_idx ON " + tableName + " (" + columns + ")");
	}

	private String getTableName(Class<?> clazz)
	{
		return clazz.getSimpleName()
				.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
				.replaceAll("[^A-Za-z0-9_]", "_")
				.toLowerCase();
	}

	@Override
	public <T extends Persistable> void put(T obj)
	{
		rejectEntityBackedClass(obj.getClass());
		upsert(obj, false);
	}

	@Override
	public <T extends Persistable> void putIfLatest(T obj)
	{
		rejectEntityBackedClass(obj.getClass());
		upsert(obj, true);
	}

	@SneakyThrows
	private <T extends Persistable> void upsert(@NonNull T obj, boolean conditional)
	{
		Persistable.validate(obj);

		RowData row = RowData.from(obj, mapper);
		String tableName = getTableName(obj.getClass());
		ensureTable(obj.getClass());
		String sql = conditional
				? "INSERT INTO " + tableName + " " +
				  "(id, storage_bucket, payload_json, date_value, rating_value, rating_abs_value, location_value, impact_value, impact_abs_value, hot_value, last_update_value, updated_at) " +
				  "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
				  "ON CONFLICT (id) DO UPDATE SET " +
				  "storage_bucket = EXCLUDED.storage_bucket, " +
				  "payload_json = EXCLUDED.payload_json, " +
				  "date_value = EXCLUDED.date_value, " +
				  "rating_value = EXCLUDED.rating_value, " +
				  "rating_abs_value = EXCLUDED.rating_abs_value, " +
				  "location_value = EXCLUDED.location_value, " +
				  "impact_value = EXCLUDED.impact_value, " +
				  "impact_abs_value = EXCLUDED.impact_abs_value, " +
				  "hot_value = EXCLUDED.hot_value, " +
				  "last_update_value = EXCLUDED.last_update_value, " +
				  "updated_at = NOW() " +
				  "WHERE " + tableName + ".last_update_value IS NULL OR " + tableName + ".last_update_value <= EXCLUDED.last_update_value"
				: "INSERT INTO " + tableName + " " +
				  "(id, storage_bucket, payload_json, date_value, rating_value, rating_abs_value, location_value, impact_value, impact_abs_value, hot_value, last_update_value, updated_at) " +
				  "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW()) " +
				  "ON CONFLICT (id) DO UPDATE SET " +
				  "storage_bucket = EXCLUDED.storage_bucket, " +
				  "payload_json = EXCLUDED.payload_json, " +
				  "date_value = EXCLUDED.date_value, " +
				  "rating_value = EXCLUDED.rating_value, " +
				  "rating_abs_value = EXCLUDED.rating_abs_value, " +
				  "location_value = EXCLUDED.location_value, " +
				  "impact_value = EXCLUDED.impact_value, " +
				  "impact_abs_value = EXCLUDED.impact_abs_value, " +
				  "hot_value = EXCLUDED.hot_value, " +
				  "last_update_value = EXCLUDED.last_update_value, " +
				  "updated_at = NOW()";

		if (conditional && row.lastUpdateValue == null) {
			throw new IllegalArgumentException("Attribute 'lastUpdate' is required for conditional postgres put on " + obj.getClass().getName());
		}

		try (Connection conn = requireDataSource().getConnection();
		     PreparedStatement ps = conn.prepareStatement(sql)) {
			int index = 1;
			ps.setString(index++, row.id);
			ps.setString(index++, row.storageBucket);
			ps.setString(index++, row.payloadJson);
			ps.setString(index++, row.dateValue);
			setNullableLong(ps, index++, row.ratingValue);
			setNullableLong(ps, index++, row.ratingAbsValue);
			ps.setString(index++, row.locationValue);
			setNullableLong(ps, index++, row.impactValue);
			setNullableLong(ps, index++, row.impactAbsValue);
			setNullableLong(ps, index++, row.hotValue);
			ps.setString(index++, row.lastUpdateValue);

			int updated = ps.executeUpdate();
			if (conditional && updated == 0) {
				throw ConditionalCheckFailedException.builder()
						.message("Conditional postgres put was rejected for id " + row.id)
						.build();
			}
		}
	}

	@Override
	public <T extends Persistable> Optional<T> get(String id, Class<T> clazz)
	{
		rejectEntityBackedClass(clazz);
		if (StringUtils.isBlank(id)) {
			return Optional.empty();
		}

		final String tableName = getTableName(clazz);
		ensureTable(clazz);
		final String sql = "SELECT payload_json FROM " + tableName + " WHERE id = ?";
		try (Connection conn = requireDataSource().getConnection();
		     PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, id);

			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return Optional.empty();
				}
				return Optional.of(mapper.readValue(rs.getString(1), clazz));
			}
		}
		catch (SQLException e) {
			throw new RuntimeException("Unable to read " + clazz.getName() + " from Postgres", e);
		}
		catch (Exception e) {
			throw new RuntimeException("Unable to deserialize " + clazz.getName() + " from Postgres", e);
		}
	}

	@Override
	public <T extends Persistable> boolean exists(String id, Class<T> clazz)
	{
		return get(id, clazz).isPresent();
	}

	@Override
	public <T extends Persistable> long count(Class<T> clazz)
	{
		rejectEntityBackedClass(clazz);
		final String tableName = getTableName(clazz);
		ensureTable(clazz);
		final String sql = "SELECT COUNT(*) FROM " + tableName;
		try (Connection conn = requireDataSource().getConnection();
		     PreparedStatement ps = conn.prepareStatement(sql)) {
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
		catch (SQLException e) {
			throw new RuntimeException("Unable to count " + clazz.getName() + " rows in Postgres", e);
		}
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz)
	{
		rejectEntityBackedClass(clazz);
		final String storageBucket = Persistable.getClassStorageBucket(clazz, null);
		return query(clazz, -1, null, null, null, null, storageBucket);
	}

	public <T extends Persistable> PaginatedList<T> query(Class<T> clazz, String datasetKey, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey)
	{
		rejectEntityBackedClass(clazz);
		final String storageBucket = Persistable.getClassStorageBucket(clazz, datasetKey);
		return queryInternal(clazz, pageSize, index, ascending, exclusiveStartKey, sortKey, storageBucket);
	}

	@Override
	public <T extends Persistable> List<T> query(Class<T> clazz, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey, String storageBucket)
	{
		rejectEntityBackedClass(clazz);
		return queryInternal(clazz, pageSize, index, ascending, exclusiveStartKey, sortKey, storageBucket);
	}

	private <T extends Persistable> PaginatedList<T> queryInternal(Class<T> clazz, int pageSize, String index, Boolean ascending, String exclusiveStartKey, String sortKey, String storageBucket)
	{
		ensureTable(clazz);

		if (StringUtils.isBlank(index)) {
			index = Persistable.OBJECT_BY_DATE_INDEX;
		}
		if (ascending == null) {
			ascending = Boolean.TRUE;
		}

		final String column = columnForIndex(index);
		final boolean numericColumn = isNumericColumn(column);
		final Cursor cursor = Cursor.parse(exclusiveStartKey, numericColumn);
		final String orderDirection = ascending ? "ASC" : "DESC";
		final List<Object> params = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT payload_json FROM " + getTableName(clazz) + " WHERE storage_bucket = ?");
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
			sql.append(column).append(ascending ? " > ?" : " < ?");
			sql.append(" OR (").append(column).append(" = ? AND id ").append(ascending ? ">" : "<").append(" ?))");
			params.add(cursor.value);
			params.add(cursor.value);
			params.add(cursor.id);
		}

		sql.append(" ORDER BY ").append(column).append(" ").append(orderDirection).append(" NULLS LAST, id ").append(orderDirection);
		if (pageSize > 0) {
			sql.append(" LIMIT ?");
			params.add(pageSize);
		}

		try (Connection conn = requireDataSource().getConnection();
		     PreparedStatement ps = conn.prepareStatement(sql.toString())) {
			int paramIndex = 1;
			for (Object param : params) {
				if (param instanceof Long longValue) {
					ps.setLong(paramIndex++, longValue);
				} else {
					ps.setString(paramIndex++, Objects.toString(param, null));
				}
			}

			List<T> rows = new ArrayList<>();
			String lastEvaluatedKey = null;
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					T item = mapper.readValue(rs.getString(1), clazz);
					rows.add(item);

					Object lastValue = readProperty(item, propertyForIndex(index));
					if (lastValue != null) {
						lastEvaluatedKey = item.getId() + CURSOR_DELIMITER + stringify(lastValue);
					} else {
						lastEvaluatedKey = item.getId();
					}
				}
			}

			return new PaginatedList<>(rows, pageSize, exclusiveStartKey, lastEvaluatedKey);
		}
		catch (SQLException e) {
			throw new RuntimeException("Unable to query " + clazz.getName() + " from Postgres", e);
		}
		catch (Exception e) {
			throw new RuntimeException("Unable to deserialize " + clazz.getName() + " from Postgres", e);
		}
	}

	private String columnForIndex(String index)
	{
		if (index.equals(Persistable.OBJECT_BY_DATE_INDEX)) {
			return "date_value";
		} else if (index.equals(Persistable.OBJECT_BY_RATING_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX)) {
			return "rating_value";
		} else if (index.equals(Persistable.OBJECT_BY_RATING_ABS_INDEX)) {
			return "rating_abs_value";
		} else if (index.equals(Persistable.OBJECT_BY_LOCATION_INDEX)) {
			return "location_value";
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX)) {
			return "impact_value";
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_ABS_INDEX)) {
			return "impact_abs_value";
		} else if (index.equals(Persistable.OBJECT_BY_HOT_INDEX)) {
			return "hot_value";
		}

		throw new UnsupportedOperationException("Unsupported Postgres index " + index);
	}

	private String propertyForIndex(String index)
	{
		if (index.equals(Persistable.OBJECT_BY_DATE_INDEX)) {
			return "date";
		} else if (index.equals(Persistable.OBJECT_BY_RATING_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_RATING_INDEX)) {
			return "rating";
		} else if (index.equals(Persistable.OBJECT_BY_RATING_ABS_INDEX)) {
			return "ratingAbs";
		} else if (index.equals(Persistable.OBJECT_BY_LOCATION_INDEX)) {
			return "location";
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_INDEX) || index.equals(Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX)) {
			return "impact";
		} else if (index.equals(Persistable.OBJECT_BY_IMPACT_ABS_INDEX)) {
			return "impactAbs";
		} else if (index.equals(Persistable.OBJECT_BY_HOT_INDEX)) {
			return "hot";
		}

		throw new UnsupportedOperationException("Unsupported Postgres index " + index);
	}

	private boolean isNumericColumn(String column)
	{
		return column.endsWith("_value") && !column.equals("date_value") && !column.equals("location_value") && !column.equals("last_update_value");
	}

	private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException
	{
		if (value == null) {
			ps.setNull(index, Types.BIGINT);
		} else {
			ps.setLong(index, value);
		}
	}

	@SneakyThrows
	private Object readProperty(Object target, String name)
	{
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

	private String stringify(Object value)
	{
		if (value == null) {
			return null;
		}
		if (value instanceof OffsetDateTime odt) {
			return OFFSET_DATE_TIME.format(odt);
		}
		if (value instanceof ZonedDateTime zdt) {
			return OFFSET_DATE_TIME.format(zdt.toOffsetDateTime());
		}
		if (value instanceof Instant instant) {
			return instant.toString();
		}
		if (value instanceof LocalDate localDate) {
			return localDate.toString();
		}
		if (value instanceof LocalDateTime localDateTime) {
			return localDateTime.toString();
		}
		return String.valueOf(value);
	}

	private Long toLong(Object value)
	{
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.longValue();
		}
		return Long.valueOf(String.valueOf(value));
	}

	private record RowData(
			String id,
			String storageBucket,
			String payloadJson,
			String dateValue,
			Long ratingValue,
			Long ratingAbsValue,
			String locationValue,
			Long impactValue,
			Long impactAbsValue,
			Long hotValue,
			String lastUpdateValue)
	{
		private static RowData from(Persistable obj, ObjectMapper mapper)
		{
			PostgresPersistenceService helper = new PostgresPersistenceService();
			return new RowData(
					obj.getId(),
					Objects.requireNonNullElse(obj.getStorageBucket(), Persistable.getClassStorageBucket(obj.getClass(), null)),
					writeJson(mapper, obj),
					helper.stringify(helper.readProperty(obj, "date")),
					helper.toLong(helper.readProperty(obj, "rating")),
					helper.toLong(helper.readProperty(obj, "ratingAbs")),
					helper.stringify(helper.readProperty(obj, "location")),
					helper.toLong(helper.readProperty(obj, "impact")),
					helper.toLong(helper.readProperty(obj, "impactAbs")),
					helper.toLong(helper.readProperty(obj, "hot")),
					helper.stringify(helper.readProperty(obj, "lastUpdate")));
		}

		private static String writeJson(ObjectMapper mapper, Persistable obj)
		{
			try {
				return mapper.writeValueAsString(obj);
			}
			catch (Exception e) {
				throw new RuntimeException("Unable to serialize " + obj.getClass().getName() + " for Postgres storage", e);
			}
		}
	}

	private static final class Cursor
	{
		private final String id;
		private final Object value;

		private Cursor(String id, Object value)
		{
			this.id = id;
			this.value = value;
		}

		private static Cursor parse(String raw, boolean numericColumn)
		{
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
	}
}

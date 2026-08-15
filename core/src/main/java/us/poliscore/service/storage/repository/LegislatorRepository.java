package us.poliscore.service.storage.repository;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.legislator.Legislator;
import us.poliscore.model.legislator.LegislatorIssueStat;
import us.poliscore.service.storage.PaginatedList;

@ApplicationScoped
@Transactional
public class LegislatorRepository extends AbstractPostgresEntityRepository<Legislator> {

	@Override
	protected Class<Legislator> entityClass() {
		return Legislator.class;
	}

	@Override
	protected String tableName() {
		return "legislator";
	}

	@Override
	public void put(Legislator entity) {
		putAll(List.of(entity));
	}

	public java.util.Optional<Legislator> getFirstPage(String id) {
		Query query = requireEntityManager().createNativeQuery("""
				SELECT
					id, last_update_value, name, officialurl, lisid, interpretation, media_coverage, legiscanid, birthday,
					impactmap, terms, CAST('[]' AS jsonb) AS interactions, interactions_first_page, storage_bucket,
					date_value, rating_value, rating_abs_value, location_value, impact_value, impact_abs_value
				FROM
				""" + qualifiedTableName() + " WHERE id = ?", Legislator.class);
		query.setParameter(1, id);
		List<Legislator> rows = readOnlyResultList(query);
		return rows.stream().findFirst();
	}

	public java.util.Optional<Legislator> getAllInteractions(String id) {
		return get(id);
	}

	@Override
	public void putIfLatest(Legislator entity) {
		putAllIfLatest(List.of(entity));
	}

	public void putAll(List<Legislator> legislators) {
		executeBatch(upsertSql(), legislators, this::bindUpsert);
	}

	public void putAllIfLatest(List<Legislator> legislators) {
		executeBatch(upsertIfLatestSql(), legislators, this::bindUpsert);
	}

	public int deleteMissingFromStorageBucket(String storageBucket, Set<String> retainedIds) {
		if (retainedIds == null || retainedIds.isEmpty()) {
			return 0;
		}

		List<String> ids = new ArrayList<>(retainedIds);
		String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(", "));
		Query query = requireEntityManager().createNativeQuery("""
				DELETE FROM %s
				WHERE storage_bucket = ?
				  AND id NOT IN (%s)
				""".formatted(qualifiedTableName(), placeholders));
		query.setParameter(1, storageBucket);
		for (int i = 0; i < ids.size(); i++) {
			query.setParameter(i + 2, ids.get(i));
		}

		return query.executeUpdate();
	}

	public List<LegislatorIssueStat> queryIssueStats(int pageSize, String index, Boolean ascending, String startKey, String storageBucket) {
		TrackedIssue issue = issueFromStorageBucket(storageBucket);
		String legislatorStorageBucket = toLegislatorStorageBucket(storageBucket);
		boolean resolvedAscending = ascending == null || ascending;
		String orderExpression = issueOrderExpression(index, issue);
		Cursor cursor = Cursor.parse(startKey, true);
		List<Object> params = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedTableName()).append(" WHERE storage_bucket = ?");
		params.add(legislatorStorageBucket);

		if (cursor != null) {
			sql.append(" AND (");
			sql.append(orderExpression).append(resolvedAscending ? " > ?" : " < ?");
			sql.append(" OR (").append(orderExpression).append(" = ? AND id ").append(resolvedAscending ? ">" : "<").append(" ?))");
			params.add(cursor.value());
			params.add(cursor.value());
			params.add(cursor.id());
		}

		String orderDirection = resolvedAscending ? "ASC" : "DESC";
		sql.append(" ORDER BY ").append(orderExpression).append(" ").append(orderDirection).append(", id ").append(orderDirection);
		if (pageSize > 0) {
			sql.append(" LIMIT ?");
			params.add(pageSize);
		}

		Query query = requireEntityManager().createNativeQuery(sql.toString(), Legislator.class);
		for (int i = 0; i < params.size(); i++) {
			query.setParameter(i + 1, params.get(i));
		}

		List<Legislator> legislators = readOnlyResultList(query);
		return legislators.stream()
				.map(legislator -> new LegislatorIssueStat(issue, legislator.getImpact(issue), legislator))
				.toList();
	}

	@Override
	public PaginatedList<Legislator> query(String datasetKey, int pageSize, String index, Boolean ascending, String startKey, String sortKey) {
		return queryListPage(pageSize, index, ascending, startKey, sortKey, Persistable.getClassStorageBucket(entityClass(), datasetKey));
	}

	@Override
	public PaginatedList<Legislator> query(int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		return queryListPage(pageSize, index, ascending, startKey, sortKey, storageBucket);
	}

	public PaginatedList<Legislator> queryListPage(int pageSize, String index, Boolean ascending, String startKey, String sortKey, String storageBucket) {
		String resolvedIndex = org.apache.commons.lang3.StringUtils.defaultIfBlank(index, Persistable.OBJECT_BY_DATE_INDEX);
		boolean resolvedAscending = ascending == null || ascending;
		String column = columnForIndex(resolvedIndex);
		boolean numericColumn = isNumericSortColumn(column);
		Cursor cursor = Cursor.parse(startKey, numericColumn);
		List<Object> params = new ArrayList<>();

		StringBuilder sql = new StringBuilder("""
				SELECT
					id, last_update_value, name, officialurl, lisid, interpretation, CAST('[]' AS jsonb) AS media_coverage, legiscanid, birthday,
					impactmap, terms, CAST('[]' AS jsonb) AS interactions, CAST('[]' AS jsonb) AS interactions_first_page, storage_bucket, date_value,
					rating_value, rating_abs_value, location_value, impact_value, impact_abs_value
				FROM
				""").append(qualifiedTableName()).append(" WHERE storage_bucket = ?");
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
			params.add(cursor.value());
			params.add(cursor.value());
			params.add(cursor.id());
		}

		String orderDirection = resolvedAscending ? "ASC" : "DESC";
		sql.append(" ORDER BY ").append(column).append(" ").append(orderDirection).append(" NULLS LAST, id ").append(orderDirection);
		if (pageSize > 0) {
			sql.append(" LIMIT ?");
			params.add(pageSize);
		}

		Query query = requireEntityManager().createNativeQuery(sql.toString(), Legislator.class);
		for (int i = 0; i < params.size(); i++) {
			query.setParameter(i + 1, params.get(i));
		}

		List<Legislator> rows = readOnlyResultList(query);
		String lastEvaluatedKey = null;
		for (Legislator item : rows) {
			Object lastValue = readSortProperty(item, resolvedIndex);
			lastEvaluatedKey = lastValue == null ? item.getId() : item.getId() + CURSOR_DELIMITER + String.valueOf(lastValue);
		}

		return new us.poliscore.service.storage.PaginatedList<>(rows, pageSize, startKey, lastEvaluatedKey);
	}

	private void bindUpsert(java.sql.PreparedStatement stmt, Legislator legislator) throws java.sql.SQLException {
		int i = 1;
		stmt.setString(i++, legislator.getId());
		stmt.setString(i++, legislator.getLastUpdate() == null ? null : legislator.getLastUpdate().toString());
		setJson(stmt, i++, legislator.getName());
		stmt.setString(i++, legislator.getOfficialUrl());
		stmt.setString(i++, legislator.getLisId());
		setJson(stmt, i++, legislator.getInterpretation());
		setJson(stmt, i++, legislator.getMediaCoverage());
		stmt.setObject(i++, legislator.getLegiscanId(), Types.INTEGER);
		stmt.setObject(i++, legislator.getBirthday());
		setJson(stmt, i++, legislator.getImpactMap());
		setJson(stmt, i++, legislator.getTerms());
		setJson(stmt, i++, legislator.getInteractionsAll());
		setJson(stmt, i++, legislator.getInteractionsFirstPage());
		stmt.setString(i++, legislator.getStorageBucket());
		stmt.setString(i++, legislator.getDate() == null ? null : legislator.getDate().toString());
		stmt.setObject(i++, legislator.getRating() == null ? null : Long.valueOf(legislator.getRating()), Types.BIGINT);
		stmt.setObject(i++, legislator.getRatingAbs() == null ? null : Long.valueOf(legislator.getRatingAbs()), Types.BIGINT);
		stmt.setString(i++, legislator.getTerms() == null || legislator.getTerms().isEmpty() ? null : legislator.getLocation());
		stmt.setObject(i++, legislator.getImpact(), Types.BIGINT);
		stmt.setObject(i++, legislator.getImpact() == null ? null : Math.abs(legislator.getImpact()), Types.BIGINT);
	}

	private TrackedIssue issueFromStorageBucket(String storageBucket) {
		int lastSlash = storageBucket.lastIndexOf('/');
		if (lastSlash == -1 || lastSlash == storageBucket.length() - 1) {
			throw new IllegalArgumentException("Unable to parse tracked issue from storage bucket " + storageBucket);
		}

		return TrackedIssue.valueOf(storageBucket.substring(lastSlash + 1));
	}

	private String toLegislatorStorageBucket(String issueStorageBucket) {
		int firstSlash = issueStorageBucket.indexOf('/');
		int lastSlash = issueStorageBucket.lastIndexOf('/');
		if (firstSlash == -1 || lastSlash <= firstSlash) {
			throw new IllegalArgumentException("Unable to parse legislator storage bucket from " + issueStorageBucket);
		}

		return Legislator.ID_CLASS_PREFIX + issueStorageBucket.substring(firstSlash, lastSlash);
	}

	private String issueOrderExpression(String index, TrackedIssue issue) {
		String issueKey = issue.name();
		if (Persistable.OBJECT_BY_ISSUE_RATING_INDEX.equals(index)) {
			return "COALESCE(CAST(interpretation -> 'issueStats' -> 'stats' ->> '" + issueKey + "' AS BIGINT), 0)";
		}
		if (Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX.equals(index)) {
			return "COALESCE(CAST(impactmap ->> '" + issueKey + "' AS BIGINT), 0)";
		}

		throw new UnsupportedOperationException("Unsupported issue sort index " + index);
	}

	private boolean isNumericSortColumn(String column) {
		return column.endsWith("_value") && !column.equals("date_value") && !column.equals("location_value");
	}

	private Object readSortProperty(Legislator legislator, String index) {
		if (Persistable.OBJECT_BY_DATE_INDEX.equals(index)) {
			return legislator.getDateValue();
		}
		if (Persistable.OBJECT_BY_RATING_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_RATING_INDEX.equals(index)) {
			return legislator.getRatingValue();
		}
		if (Persistable.OBJECT_BY_RATING_ABS_INDEX.equals(index)) {
			return legislator.getRatingAbsValue();
		}
		if (Persistable.OBJECT_BY_LOCATION_INDEX.equals(index)) {
			return legislator.getLocationValue();
		}
		if (Persistable.OBJECT_BY_IMPACT_INDEX.equals(index) || Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX.equals(index)) {
			return legislator.getImpactValue();
		}
		if (Persistable.OBJECT_BY_IMPACT_ABS_INDEX.equals(index)) {
			return legislator.getImpactAbsValue();
		}

		throw new UnsupportedOperationException("Unsupported Postgres index " + index);
	}

	private String upsertSql() {
		return """
				INSERT INTO %s (
					id, last_update_value, name, officialurl, lisid, interpretation, media_coverage, legiscanid, birthday,
					impactmap, terms, interactions, interactions_first_page, storage_bucket, date_value,
					rating_value, rating_abs_value, location_value, impact_value, impact_abs_value
				) VALUES (
					?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
					CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?
				)
				ON CONFLICT (id) DO UPDATE SET
					last_update_value = EXCLUDED.last_update_value,
					name = EXCLUDED.name,
					officialurl = EXCLUDED.officialurl,
					lisid = EXCLUDED.lisid,
					interpretation = EXCLUDED.interpretation,
					media_coverage = EXCLUDED.media_coverage,
					legiscanid = EXCLUDED.legiscanid,
					birthday = EXCLUDED.birthday,
					impactmap = EXCLUDED.impactmap,
					terms = EXCLUDED.terms,
					interactions = EXCLUDED.interactions,
					interactions_first_page = EXCLUDED.interactions_first_page,
					storage_bucket = EXCLUDED.storage_bucket,
					date_value = EXCLUDED.date_value,
					rating_value = EXCLUDED.rating_value,
					rating_abs_value = EXCLUDED.rating_abs_value,
					location_value = EXCLUDED.location_value,
					impact_value = EXCLUDED.impact_value,
					impact_abs_value = EXCLUDED.impact_abs_value
				""".formatted(qualifiedTableName());
	}

	private String upsertIfLatestSql() {
		String table = qualifiedTableName();
		return upsertSql() + """
				WHERE %s.last_update_value IS NULL
				   OR (EXCLUDED.last_update_value IS NOT NULL AND EXCLUDED.last_update_value > %s.last_update_value)
				""".formatted(table, table);
	}
}

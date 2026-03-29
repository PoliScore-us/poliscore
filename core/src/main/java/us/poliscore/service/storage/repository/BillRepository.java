package us.poliscore.service.storage.repository;

import java.time.LocalDateTime;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import us.poliscore.model.Persistable;
import us.poliscore.model.TrackedIssue;
import us.poliscore.model.bill.Bill;
import us.poliscore.model.bill.BillIssueStat;

@ApplicationScoped
@Transactional
public class BillRepository extends AbstractPostgresEntityRepository<Bill> {

	@Override
	protected Class<Bill> entityClass() {
		return Bill.class;
	}

	@Override
	protected String tableName() {
		return "bill";
	}

	@Override
	public void put(Bill entity) {
		putAll(List.of(entity));
	}

	@Override
	public void putIfLatest(Bill entity) {
		putAllIfLatest(List.of(entity));
	}

	public void putAll(List<Bill> bills) {
		executeBatch(upsertSql(), bills, this::bindUpsert);
	}

	public void putAllIfLatest(List<Bill> bills) {
		executeBatch(upsertIfLatestSql(), bills, this::bindUpsert);
	}

	public Map<String, LocalDateTime> getLastUpdateMap(String datasetKey) {
		String storageBucket = Persistable.getClassStorageBucket(Bill.class, datasetKey);
		List<Tuple> rows = requireEntityManager().createQuery(
				"select b.id as id, b.lastUpdate as lastUpdate from Bill b where b.storageBucketValue = :storageBucket",
				Tuple.class)
			.setParameter("storageBucket", storageBucket)
			.getResultList();

		Map<String, LocalDateTime> result = new HashMap<>(rows.size());
		for (Tuple row : rows) {
			result.put(row.get("id", String.class), row.get("lastUpdate", LocalDateTime.class));
		}
		return result;
	}

	public List<BillIssueStat> queryIssueStats(int pageSize, String index, Boolean ascending, String startKey, String storageBucket) {
		TrackedIssue issue = issueFromStorageBucket(storageBucket);
		String billStorageBucket = toBillStorageBucket(storageBucket);
		boolean resolvedAscending = ascending == null || ascending;
		String orderExpression = issueOrderExpression(index, issue);
		Cursor cursor = Cursor.parse(startKey, true);
		List<Object> params = new ArrayList<>();

		StringBuilder sql = new StringBuilder("SELECT * FROM ").append(qualifiedTableName()).append(" WHERE storage_bucket = ?");
		params.add(billStorageBucket);

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

		Query query = requireEntityManager().createNativeQuery(sql.toString(), Bill.class);
		for (int i = 0; i < params.size(); i++) {
			query.setParameter(i + 1, params.get(i));
		}

		@SuppressWarnings("unchecked")
		List<Bill> bills = query.getResultList();
		return bills.stream()
				.map(bill -> new BillIssueStat(issue, bill.getImpact(issue), bill))
				.toList();
	}

	private void bindUpsert(java.sql.PreparedStatement stmt, Bill bill) throws java.sql.SQLException {
		int i = 1;
		stmt.setString(i++, bill.getId());
		stmt.setString(i++, bill.getLastUpdate() == null ? null : bill.getLastUpdate().toString());
		stmt.setString(i++, bill.getType());
		stmt.setInt(i++, bill.getNumber());
		if (bill.getOriginatingChamber() == null) {
			stmt.setNull(i++, Types.INTEGER);
		} else {
			stmt.setInt(i++, bill.getOriginatingChamber().ordinal());
		}
		setJson(stmt, i++, bill.getStatus());
		stmt.setString(i++, bill.getName());
		stmt.setInt(i++, bill.getLegiscanId());
		stmt.setString(i++, bill.getOfficialUrl());
		setJson(stmt, i++, bill.getSponsor());
		setJson(stmt, i++, bill.getCosponsors());
		stmt.setObject(i++, bill.getIntroducedDate());
		stmt.setObject(i++, bill.getLastActionDate());
		setJson(stmt, i++, bill.getInterpretation());
		setJson(stmt, i++, bill.getIssueImpactMap());
		setJson(stmt, i++, bill.getCboAnalysis());
		stmt.setString(i++, bill.getStorageBucket());
		stmt.setString(i++, bill.getDate() == null ? null : bill.getDate().toString());
		stmt.setObject(i++, bill.getInterpretation() == null || bill.getInterpretation().getRating() == null ? null : Long.valueOf(bill.getInterpretation().getRating()), Types.BIGINT);
		stmt.setObject(i++, bill.getInterpretation() == null || bill.getInterpretation().getRating() == null ? null : Long.valueOf(Math.abs(bill.getInterpretation().getRating())), Types.BIGINT);
		stmt.setObject(i++, canCalculateDerivedMetrics(bill) ? Long.valueOf(bill.getImpact()) : null, Types.BIGINT);
		stmt.setObject(i++, canCalculateDerivedMetrics(bill) ? Long.valueOf(bill.getImpactAbs()) : null, Types.BIGINT);
		stmt.setObject(i++, canCalculateDerivedMetrics(bill) ? Long.valueOf(bill.getHot()) : null, Types.BIGINT);
	}

	private boolean canCalculateDerivedMetrics(Bill bill) {
		return bill.getInterpretation() != null && bill.getStatus() != null && bill.getLastActionDate() != null;
	}

	private String upsertSql() {
		return """
				INSERT INTO %s (
					id, last_update_value, type, number, originatingchamber, status, name, legiscanid,
					officialurl, sponsor, cosponsors, introduceddate, lastactiondate, interpretation, issue_impact_map,
					cboanalysis, storage_bucket, date_value, rating_value, rating_abs_value, impact_value,
					impact_abs_value, hot_value
				) VALUES (
					?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
					CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?
				)
				ON CONFLICT (id) DO UPDATE SET
					last_update_value = EXCLUDED.last_update_value,
					type = EXCLUDED.type,
					number = EXCLUDED.number,
					originatingchamber = EXCLUDED.originatingchamber,
					status = EXCLUDED.status,
					name = EXCLUDED.name,
					legiscanid = EXCLUDED.legiscanid,
					officialurl = EXCLUDED.officialurl,
					sponsor = EXCLUDED.sponsor,
					cosponsors = EXCLUDED.cosponsors,
					introduceddate = EXCLUDED.introduceddate,
					lastactiondate = EXCLUDED.lastactiondate,
					interpretation = EXCLUDED.interpretation,
					issue_impact_map = EXCLUDED.issue_impact_map,
					cboanalysis = EXCLUDED.cboanalysis,
					storage_bucket = EXCLUDED.storage_bucket,
					date_value = EXCLUDED.date_value,
					rating_value = EXCLUDED.rating_value,
					rating_abs_value = EXCLUDED.rating_abs_value,
					impact_value = EXCLUDED.impact_value,
					impact_abs_value = EXCLUDED.impact_abs_value,
					hot_value = EXCLUDED.hot_value
				""".formatted(qualifiedTableName());
	}

	private String upsertIfLatestSql() {
		String table = qualifiedTableName();
		return upsertSql() + """
				WHERE %s.last_update_value IS NULL
				   OR (EXCLUDED.last_update_value IS NOT NULL AND EXCLUDED.last_update_value > %s.last_update_value)
				""".formatted(table, table);
	}

	private TrackedIssue issueFromStorageBucket(String storageBucket) {
		int lastSlash = storageBucket.lastIndexOf('/');
		if (lastSlash == -1 || lastSlash == storageBucket.length() - 1) {
			throw new IllegalArgumentException("Unable to parse tracked issue from storage bucket " + storageBucket);
		}

		return TrackedIssue.valueOf(storageBucket.substring(lastSlash + 1));
	}

	private String toBillStorageBucket(String issueStorageBucket) {
		int firstSlash = issueStorageBucket.indexOf('/');
		int lastSlash = issueStorageBucket.lastIndexOf('/');
		if (firstSlash == -1 || lastSlash <= firstSlash) {
			throw new IllegalArgumentException("Unable to parse bill storage bucket from " + issueStorageBucket);
		}

		return Bill.ID_CLASS_PREFIX + issueStorageBucket.substring(firstSlash, lastSlash);
	}

	private String issueOrderExpression(String index, TrackedIssue issue) {
		String issueKey = issue.name();
		if (Persistable.OBJECT_BY_ISSUE_RATING_INDEX.equals(index)) {
			return "COALESCE(CAST(interpretation -> 'issueStats' -> 'stats' ->> '" + issueKey + "' AS BIGINT), 0)";
		}
		if (Persistable.OBJECT_BY_ISSUE_IMPACT_INDEX.equals(index)) {
			return "COALESCE(CAST(issue_impact_map ->> '" + issueKey + "' AS BIGINT), 0)";
		}

		throw new UnsupportedOperationException("Unsupported issue sort index " + index);
	}
}

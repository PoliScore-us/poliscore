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
import us.poliscore.model.bill.BillText;
import us.poliscore.model.bill.FundingSnapshot;
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

	/** Refresh the daily ranking without resending bill text and interpretations. */
	public void refreshHotValues(List<Bill> bills) {
		executeBatch("UPDATE " + qualifiedTableName() + " SET hot_value = ? WHERE id = ?", bills, (stmt, bill) -> {
			stmt.setObject(1, canCalculateDerivedMetrics(bill) ? Long.valueOf(bill.getHot()) : null, Types.BIGINT);
			stmt.setString(2, bill.getId());
		});
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

    private void limitFundingStatement(int millis) {
        requireEntityManager().createNativeQuery("SELECT set_config('statement_timeout', :timeout, true)")
                .setParameter("timeout", Integer.toString(millis)).getSingleResult();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public List<Bill> fundingCandidates(Bill bill, int timeoutMillis) {
        limitFundingStatement(timeoutMillis);
        String sql = "SELECT b.* FROM " + qualifiedTableName() + """
                 b WHERE b.id LIKE ? AND b.id <> ? AND b.introduceddate BETWEEN ? AND ?
                 AND (lower(b.status ->> 'description') = 'law' OR b.status ->> 'sourceStatus' LIKE 'ENACTED:%')
                 AND (lower(b.name) LIKE '%appropriation%' OR lower(b.name) LIKE '%long bill%')
                 ORDER BY CASE WHEN lower(b.name) LIKE '%full-year continuing appropriation%' THEN 0 ELSE 1 END,
                          b.introduceddate DESC, b.id LIMIT 24
                """;
        return readOnlyResultList(requireEntityManager().createNativeQuery(sql, Bill.class)
                .setParameter(1, "BIL/" + bill.getNamespace() + "/%").setParameter(2, bill.getId())
                .setParameter(3, bill.getIntroducedDate().minusYears(3)).setParameter(4, bill.getIntroducedDate())
                .setHint("jakarta.persistence.query.timeout", timeoutMillis));
    }

    /** Patch only the text metadata that we read. Concurrent analysis and bill updates survive. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean saveFunding(String billId, BillText text,
            FundingSnapshot funding, int timeoutMillis) {
        limitFundingStatement(timeoutMillis);
        var identity = new HashMap<String,Object>();
        identity.put("id", text.getId());
        if (text.getVersion() != null) identity.put("version", text.getVersion());
        if (text.getLastUpdate() != null) identity.put("lastUpdate", text.getLastUpdate());
        if (text.getLegiscanId() != null) identity.put("legiscanId", text.getLegiscanId());
        String sameText = """
                jsonb_strip_nulls(jsonb_build_object('id', item->'id', 'version', item->'version',
                    'lastUpdate', item->'lastUpdate', 'legiscanId', item->'legiscanId')) = CAST(:identity AS jsonb)
                """;
        String sql = """
                UPDATE %s SET texts = (SELECT jsonb_agg(CASE WHEN %s
                    THEN jsonb_set(item, '{funding}', CAST(:funding AS jsonb)) ELSE item END ORDER BY pos)
                    FROM jsonb_array_elements(texts) WITH ORDINALITY entries(item,pos))
                WHERE id = :id AND EXISTS (SELECT 1 FROM jsonb_array_elements(texts) item WHERE %s
                    AND (COALESCE(CAST(item->'funding'->>'extractorVersion' AS integer), 0),
                         COALESCE(CAST(item->'funding'->>'computedAt' AS bigint), 0)) <= (:revision, :computedAt))
                """.formatted(qualifiedTableName(), sameText, sameText);
        return requireEntityManager().createNativeQuery(sql).setParameter("id", billId)
                .setParameter("identity", toJson(identity)).setParameter("funding", toJson(funding))
                .setParameter("revision", funding.extractorVersion()).setParameter("computedAt", funding.computedAt())
                .setHint("jakarta.persistence.query.timeout", timeoutMillis).executeUpdate() == 1;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean restoreFundingText(Bill original, BillText text, int timeoutMillis) {
        limitFundingStatement(timeoutMillis);
        String sql = "UPDATE " + qualifiedTableName() + """
                SET texts = COALESCE(texts, CAST('[]' AS jsonb)) || jsonb_build_array(CAST(:text AS jsonb))
                WHERE id = :id AND last_update_value IS NOT DISTINCT FROM CAST(:stamp AS text)
                    AND interpretation->>'id' IS NOT DISTINCT FROM CAST(:interp AS text)
                    AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(texts) t
                                    WHERE t->>'version' IS NOT DISTINCT FROM CAST(:version AS text))
                """;
        var interp = original.getInterpretation();
        return requireEntityManager().createNativeQuery(sql)
                .setParameter("text", toJson(text)).setParameter("id", original.getId())
                .setParameter("version", text.getVersion()).setParameter("interp", interp == null ? null : interp.getId())
                .setParameter("stamp", original.getLastUpdate() == null ? null : original.getLastUpdate().toString())
                .setHint("jakarta.persistence.query.timeout", timeoutMillis).executeUpdate() == 1;
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

		List<Bill> bills = readOnlyResultList(query);
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
		setJson(stmt, i++, bill.getTexts());
		setJson(stmt, i++, bill.getInterpretation());
		setJson(stmt, i++, bill.getInterpretations());
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
					officialurl, sponsor, cosponsors, introduceddate, lastactiondate, texts, interpretation,
					interpretations, issue_impact_map, cboanalysis, storage_bucket, date_value, rating_value, rating_abs_value, impact_value,
					impact_abs_value, hot_value
				) VALUES (
					?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
					CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?
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
					texts = EXCLUDED.texts,
					interpretation = EXCLUDED.interpretation,
					interpretations = EXCLUDED.interpretations,
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

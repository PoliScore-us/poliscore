package us.poliscore.service.storage.repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.Tuple;
import jakarta.transaction.Transactional;
import us.poliscore.model.Persistable;
import us.poliscore.model.bill.Bill;

@ApplicationScoped
@Transactional
public class BillRepository extends AbstractPostgresEntityRepository<Bill> {

	private static final String UPSERT_SQL = """
			INSERT INTO bill (
				id, last_update_value, type, number, originatingchamber, status, name, legiscanid,
				officialurl, sponsor, cosponsors, introduceddate, lastactiondate, interpretation,
				cboanalysis, storage_bucket, date_value, rating_value, rating_abs_value, impact_value,
				impact_abs_value, hot_value
			) VALUES (
				?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb),
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
				cboanalysis = EXCLUDED.cboanalysis,
				storage_bucket = EXCLUDED.storage_bucket,
				date_value = EXCLUDED.date_value,
				rating_value = EXCLUDED.rating_value,
				rating_abs_value = EXCLUDED.rating_abs_value,
				impact_value = EXCLUDED.impact_value,
				impact_abs_value = EXCLUDED.impact_abs_value,
				hot_value = EXCLUDED.hot_value
			""";

	private static final String UPSERT_IF_LATEST_SQL = UPSERT_SQL + """
			WHERE bill.last_update_value IS NULL
			   OR (EXCLUDED.last_update_value IS NOT NULL AND EXCLUDED.last_update_value > bill.last_update_value)
			""";

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
		executeBatch(UPSERT_SQL, bills, this::bindUpsert);
	}

	public void putAllIfLatest(List<Bill> bills) {
		executeBatch(UPSERT_IF_LATEST_SQL, bills, this::bindUpsert);
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
}

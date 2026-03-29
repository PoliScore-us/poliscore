package us.poliscore.service.storage.repository;

import java.sql.Types;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import us.poliscore.model.legislator.Legislator;

@ApplicationScoped
@Transactional
public class LegislatorRepository extends AbstractPostgresEntityRepository<Legislator> {

	private static final String UPSERT_SQL = """
			INSERT INTO legislator (
				id, last_update_value, name, officialurl, lisid, interpretation, legiscanid, birthday,
				impactmap, terms, interactions, storage_bucket, date_value,
				rating_value, rating_abs_value, location_value, impact_value, impact_abs_value
			) VALUES (
				?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
				CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?
			)
			ON CONFLICT (id) DO UPDATE SET
				last_update_value = EXCLUDED.last_update_value,
				name = EXCLUDED.name,
				officialurl = EXCLUDED.officialurl,
				lisid = EXCLUDED.lisid,
				interpretation = EXCLUDED.interpretation,
				legiscanid = EXCLUDED.legiscanid,
				birthday = EXCLUDED.birthday,
				impactmap = EXCLUDED.impactmap,
				terms = EXCLUDED.terms,
				interactions = EXCLUDED.interactions,
				storage_bucket = EXCLUDED.storage_bucket,
				date_value = EXCLUDED.date_value,
				rating_value = EXCLUDED.rating_value,
				rating_abs_value = EXCLUDED.rating_abs_value,
				location_value = EXCLUDED.location_value,
				impact_value = EXCLUDED.impact_value,
				impact_abs_value = EXCLUDED.impact_abs_value
			""";

	private static final String UPSERT_IF_LATEST_SQL = UPSERT_SQL + """
			WHERE legislator.last_update_value IS NULL
			   OR (EXCLUDED.last_update_value IS NOT NULL AND EXCLUDED.last_update_value > legislator.last_update_value)
			""";

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

	@Override
	public void putIfLatest(Legislator entity) {
		putAllIfLatest(List.of(entity));
	}

	public void putAll(List<Legislator> legislators) {
		executeBatch(UPSERT_SQL, legislators, this::bindUpsert);
	}

	public void putAllIfLatest(List<Legislator> legislators) {
		executeBatch(UPSERT_IF_LATEST_SQL, legislators, this::bindUpsert);
	}

	private void bindUpsert(java.sql.PreparedStatement stmt, Legislator legislator) throws java.sql.SQLException {
		int i = 1;
		stmt.setString(i++, legislator.getId());
		stmt.setString(i++, legislator.getLastUpdate() == null ? null : legislator.getLastUpdate().toString());
		setJson(stmt, i++, legislator.getName());
		stmt.setString(i++, legislator.getOfficialUrl());
		stmt.setString(i++, legislator.getLisId());
		setJson(stmt, i++, legislator.getInterpretation());
		stmt.setObject(i++, legislator.getLegiscanId(), Types.INTEGER);
		stmt.setObject(i++, legislator.getBirthday());
		setJson(stmt, i++, legislator.getImpactMap());
		setJson(stmt, i++, legislator.getTerms());
		setJson(stmt, i++, legislator.getInteractions());
		stmt.setString(i++, legislator.getStorageBucket());
		stmt.setString(i++, legislator.getDate() == null ? null : legislator.getDate().toString());
		stmt.setObject(i++, legislator.getRating() == null ? null : Long.valueOf(legislator.getRating()), Types.BIGINT);
		stmt.setObject(i++, legislator.getRatingAbs() == null ? null : Long.valueOf(legislator.getRatingAbs()), Types.BIGINT);
		stmt.setString(i++, legislator.getTerms() == null || legislator.getTerms().isEmpty() ? null : legislator.getLocation());
		stmt.setObject(i++, legislator.getImpact(), Types.BIGINT);
		stmt.setObject(i++, legislator.getImpact() == null ? null : Math.abs(legislator.getImpact()), Types.BIGINT);
	}
}

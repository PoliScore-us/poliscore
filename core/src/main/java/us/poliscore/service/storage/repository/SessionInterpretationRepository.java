package us.poliscore.service.storage.repository;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import us.poliscore.model.session.SessionInterpretation;

@ApplicationScoped
@Transactional
public class SessionInterpretationRepository extends AbstractPostgresEntityRepository<SessionInterpretation> {

	@Override
	protected Class<SessionInterpretation> entityClass() {
		return SessionInterpretation.class;
	}

	@Override
	protected String tableName() {
		return "session_interpretation";
	}

	@Override
	public void put(SessionInterpretation entity) {
		putAll(List.of(entity));
	}

	@Override
	public void putIfLatest(SessionInterpretation entity) {
		put(entity);
	}

	public void putAll(List<SessionInterpretation> sessionInterpretations) {
		executeBatch(upsertSql(), sessionInterpretations, this::bindUpsert);
	}

	private void bindUpsert(java.sql.PreparedStatement stmt, SessionInterpretation sessionInterpretation) throws java.sql.SQLException {
		int i = 1;
		stmt.setString(i++, sessionInterpretation.getId());
		setJson(stmt, i++, sessionInterpretation.getSession());
		setJson(stmt, i++, sessionInterpretation.getDemocrat());
		setJson(stmt, i++, sessionInterpretation.getRepublican());
		setJson(stmt, i++, sessionInterpretation.getIndependent());
		setJson(stmt, i++, sessionInterpretation.getMetadata());
		stmt.setString(i++, sessionInterpretation.getStorageBucket());
		stmt.setString(i++, sessionInterpretation.getDate() == null ? null : sessionInterpretation.getDate().toString());
	}

	private String upsertSql() {
		return """
				INSERT INTO %s (
					id, session, democrat, republican, independent, metadata, storage_bucket, date_value
				) VALUES (
					?, CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?, ?
				)
				ON CONFLICT (id) DO UPDATE SET
					session = EXCLUDED.session,
					democrat = EXCLUDED.democrat,
					republican = EXCLUDED.republican,
					independent = EXCLUDED.independent,
					metadata = EXCLUDED.metadata,
					storage_bucket = EXCLUDED.storage_bucket,
					date_value = EXCLUDED.date_value
				""".formatted(qualifiedTableName());
	}
}

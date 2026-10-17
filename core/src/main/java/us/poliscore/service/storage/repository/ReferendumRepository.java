package us.poliscore.service.storage.repository;

import java.util.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import us.poliscore.model.referendum.*;
import us.poliscore.service.storage.PostgresSchemaSupport;
import us.poliscore.PoliscoreUtil;

@ApplicationScoped
@Transactional
public class ReferendumRepository implements PostgresPersistableRepository<Referendum> {
    @Inject EntityManager em;
    @Inject PostgresSchemaSupport schema;
    public Class<Referendum> getPersistableClass() { return Referendum.class; }
    public void put(Referendum value) {
        value.validate();
        var row = new ReferendumRecord(); row.id = value.getId(); row.storageBucket = value.getStorageBucket();
        row.payload = value; row.searchText = searchText(value); em.merge(row);
    }
    public Optional<Referendum> get(String id) {
        return Optional.ofNullable(em.find(ReferendumRecord.class, id)).map(row -> row.payload);
    }
    public boolean exists(String id) { return get(id).isPresent(); }
    public long count() { return em.createQuery("select count(r) from ReferendumRecord r", Long.class).getSingleResult(); }
    public List<Referendum> query() { return list(null, null, Integer.MAX_VALUE, 0); }
    public List<Referendum> query(int pageSize, String index, Boolean ascending, String startKey, String sortKey, String bucket) {
        if (startKey != null || sortKey != null || index != null) throw new UnsupportedOperationException("Use referendum list/search pagination");
        return list(bucket, null, pageSize > 0 ? pageSize : Integer.MAX_VALUE, 0);
    }
    public List<Referendum> list(String bucket, String search, int limit, int offset) {
        var sql = new StringBuilder("select r from ReferendumRecord r where 1=1");
        if (bucket != null) sql.append(" and r.storageBucket = :bucket");
        if (search != null && !search.isBlank()) sql.append(" and lower(r.searchText) like :search escape '!' ");
        sql.append(" order by r.id");
        var q = em.createQuery(sql.toString(), ReferendumRecord.class);
        if (bucket != null) q.setParameter("bucket", bucket);
        if (search != null && !search.isBlank()) q.setParameter("search", "%" + search.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        return q.setFirstResult(offset).setMaxResults(limit).getResultList().stream().map(r -> r.payload).toList();
    }
    public List<Referendum> search(String bucket, String text, String state, Integer year, String sort, int limit, int offset) {
        String table = schema.qualifyTable("referendum");
        var sql = new StringBuilder("select cast(payload as text) from " + table + " where 1=1");
        var args = new ArrayList<Object>();
        if (bucket != null) { sql.append(" and storage_bucket = ?"); args.add(bucket); }
        if (state != null) { sql.append(" and payload ->> 'namespace' = ?"); args.add("us/" + state); }
        if (year != null) { sql.append(" and left(payload ->> 'electionDate', 4) = ?"); args.add(year.toString()); }
        if (text != null && !text.isBlank()) {
            sql.append(" and lower(search_text) like ? escape '!' ");
            args.add("%" + text.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        String order = switch (sort) {
            case "rating" -> "cast(payload -> 'analysis' ->> 'rating' as integer) desc nulls last";
            case "title" -> "lower(coalesce(payload -> 'analysis' ->> 'title', payload ->> 'officialTitle'))";
            default -> "case when cast(payload ->> 'electionDate' as date) >= current_date then 0 else 1 end, "
                + "case when cast(payload ->> 'electionDate' as date) >= current_date then payload ->> 'electionDate' end asc, "
                + "payload ->> 'electionDate' desc, cast(payload ->> 'ballotOrder' as integer)";
        };
        sql.append(" order by ").append(order).append(", id limit ? offset ?"); args.add(limit); args.add(offset);
        var q = em.createNativeQuery(sql.toString(), String.class);
        for (int i = 0; i < args.size(); i++) q.setParameter(i + 1, args.get(i));
        return ((List<String>) q.getResultList()).stream().map(json -> {
            try { return PoliscoreUtil.getObjectMapper().readValue(json, Referendum.class); }
            catch (Exception e) { throw new IllegalStateException("Invalid stored referendum", e); }
        }).toList();
    }
    public static String searchText(Referendum value) {
        var a = value.getAnalysis();
        return String.join(" ", value.getDesignation(), value.getOfficialTitle(), value.getBallotQuestion(),
            a == null ? "" : String.join(" ", a.getTitle(), a.getNeutralSummary(), String.join(" ", a.getTopics()), String.join(" ", a.getOtherNames())));
    }
}

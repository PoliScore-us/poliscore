package us.poliscore.model.referendum;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Queryable Postgres projection of the canonical Persistable object. */
@Entity
@Table(name = "referendum", indexes = @Index(name = "referendum_bucket_idx", columnList = "storage_bucket"))
public class ReferendumRecord {
    @Id public String id;
    @Column(name = "storage_bucket", nullable = false) public String storageBucket;
    @Column(name = "search_text", columnDefinition = "text", nullable = false) public String searchText;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) public Referendum payload;
}

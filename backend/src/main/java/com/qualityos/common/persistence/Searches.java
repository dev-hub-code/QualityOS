package com.qualityos.common.persistence;

import jakarta.persistence.criteria.Predicate;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Reusable list filters. Tenant scoping is applied by Hibernate and row-level security, not here. */
public final class Searches {

    private Searches() {
    }

    public static <T> Specification<T> notArchived() {
        return (root, query, cb) -> cb.isNull(root.get("archivedAt"));
    }

    /** Case-insensitive substring match on any of the fields. Matches everything when the term is blank. */
    public static <T> Specification<T> contains(String term, String... fields) {
        if (term == null || term.isBlank()) {
            return Specification.unrestricted();
        }
        String pattern = "%" + escape(term.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(Arrays.stream(fields)
                .map(field -> cb.like(cb.lower(root.get(field)), pattern, '\\'))
                .toArray(Predicate[]::new));
    }

    public static <T> Specification<T> equalTo(String field, Object value) {
        return value == null ? Specification.unrestricted() : (root, query, cb) -> cb.equal(root.get(field), value);
    }

    private static String escape(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

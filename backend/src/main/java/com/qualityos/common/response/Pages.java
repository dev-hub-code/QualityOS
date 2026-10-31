package com.qualityos.common.response;

import com.qualityos.common.exception.ErrorCode;
import com.qualityos.common.exception.QualityOsException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Builds a {@link Pageable} from request parameters, accepting only the sort fields an endpoint allows. */
public final class Pages {

    private static final int MAX_SIZE = 100;

    private Pages() {
    }

    /** @param sort {@code field} or {@code field,desc}; null for the default */
    public static Pageable of(int page, int size, String sort, Set<String> allowedFields, String defaultField) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new QualityOsException(ErrorCode.VALIDATION_FAILED, "Page must be 0 or more and size 1 to 100.");
        }
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size, Sort.by(defaultField));
        }
        String[] parts = sort.split(",", 2);
        if (!allowedFields.contains(parts[0])) {
            throw new QualityOsException(ErrorCode.VALIDATION_FAILED, "Cannot sort by " + parts[0] + ".");
        }
        boolean descending = parts.length == 2 && parts[1].equalsIgnoreCase("desc");
        return PageRequest.of(page, size, descending ? Sort.by(parts[0]).descending() : Sort.by(parts[0]));
    }
}

package com.example.backend.util;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Utility class for paginating in-memory lists.
 * Useful for pagination after complex processing or aggregation.
 */
public class PaginationUtil {

    private PaginationUtil() {
        // Utility class
    }

    /**
     * Paginate a list based on Spring Data Pageable
     * @param content List to paginate
     * @param pageable Pagination parameters
     * @return Page object with requested content and metadata
     */
    public static <T> Page<T> paginate(List<T> content, Pageable pageable) {
        int pageSize = pageable.getPageSize();
        int pageNum = pageable.getPageNumber();
        int total = content.size();

        int fromIndex = pageNum * pageSize;
        if (fromIndex >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        int toIndex = Math.min(fromIndex + pageSize, total);
        return new PageImpl<>(content.subList(fromIndex, toIndex), pageable, total);
    }

    /**
     * Check if pagination parameters are valid
     * @param page Page number (0-indexed)
     * @param size Page size
     * @return Validated page number (minimum 0)
     */
    public static int validatePageNumber(int page) {
        return Math.max(0, page);
    }

    /**
     * Check if size parameter is valid
     * @param size Page size
     * @return Validated size (minimum 1)
     */
    public static int validatePageSize(int size) {
        return Math.max(1, size);
    }
}

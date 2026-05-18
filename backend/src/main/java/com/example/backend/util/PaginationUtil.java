package com.example.backend.util;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 인메모리 리스트 페이지네이션 유틸리티 클래스.
 *
 * <p>복잡한 처리나 집계 후 결과를 Spring Data {@link Page}로 변환할 때 사용한다.
 * DB 쿼리 수준의 페이지네이션이 어려운 경우에 한해 사용하며,
 * 대용량 데이터에는 DB 레벨 페이지네이션이 권장된다.</p>
 *
 * <p>이 클래스는 인스턴스화할 수 없는 정적 유틸리티 클래스이다.</p>
 */
public class PaginationUtil {

    private PaginationUtil() {
        // 유틸리티 클래스 - 인스턴스화 불가
    }

    /**
     * 전체 리스트를 Spring Data {@link Pageable} 기준으로 페이징하여 반환한다.
     *
     * <p>요청한 페이지 번호가 범위를 초과하면 빈 페이지를 반환한다.</p>
     *
     * @param <T>      컨텐츠 타입
     * @param content  페이징할 전체 리스트
     * @param pageable 페이지 번호(0-indexed)와 크기 정보
     * @return 요청 페이지에 해당하는 {@link Page} 객체
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
     * 페이지 번호의 유효성을 검사하여 최소 0을 보장한다.
     *
     * @param page 페이지 번호 (0-indexed)
     * @return 유효한 페이지 번호 (최소 0)
     */
    public static int validatePageNumber(int page) {
        return Math.max(0, page);
    }

    /**
     * 페이지 크기의 유효성을 검사하여 최소 1을 보장한다.
     *
     * @param size 페이지 크기
     * @return 유효한 페이지 크기 (최소 1)
     */
    public static int validatePageSize(int size) {
        return Math.max(1, size);
    }
}

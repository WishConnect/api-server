package com.wishconnect.domain.search.service;

import com.wishconnect.domain.search.dto.PopularKeywordResponse;
import com.wishconnect.domain.search.repository.PopularKeywordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PopularKeywordService {

    private static final int POPULAR_KEYWORD_COUNT = 5;

    private final PopularKeywordRepository popularKeywordRepository;

    public PopularKeywordResponse getPopularKeywords() {
        List<String> keywords = popularKeywordRepository.findPopularProviders(
                PageRequest.of(0, POPULAR_KEYWORD_COUNT)
        );
        return new PopularKeywordResponse(keywords);
    }
}

package com.wishconnect.domain.search;



import com.wishconnect.domain.search.dto.PopularKeywordResponse;
import com.wishconnect.domain.search.service.PopularKeywordService;
import com.wishconnect.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scholarships/search/popular-keywords")
@RequiredArgsConstructor
public class PopularKeywordController {

    private final PopularKeywordService popularKeywordService;

    @GetMapping
    public ApiResponse<PopularKeywordResponse> getPopularKeywords() {
        return ApiResponse.ok(popularKeywordService.getPopularKeywords());
    }
}

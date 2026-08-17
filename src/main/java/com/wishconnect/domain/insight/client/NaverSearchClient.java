package com.wishconnect.domain.insight.client;

import com.wishconnect.domain.insight.dto.NaverSearchResponse;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class NaverSearchClient {

    @Value("${naver.search.client-id}")
    private String clientId;

    @Value("${naver.search.client-secret}")
    private String clientSecret;

    private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";

    private final RestClient restClient = RestClient.builder()
            .messageConverters(converters -> {
                converters.removeIf(c -> c instanceof StringHttpMessageConverter);
                converters.add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));

                MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
                jsonConverter.setDefaultCharset(StandardCharsets.UTF_8);
                jsonConverter.setSupportedMediaTypes(List.of(
                        MediaType.APPLICATION_JSON,
                        new MediaType("text", "plain", StandardCharsets.UTF_8)  // text/plain도 JSON으로 처리
                ));
                converters.add(0, jsonConverter);
            })
            .build();

    public NaverSearchResponse searchBlog(String query, int display) {
        return search("blog", query, display);
    }

    public NaverSearchResponse searchCafe(String query, int display) {
        return search("cafearticle", query, display);
    }

    public NaverSearchResponse searchWeb(String query, int display) {
        return search("webkr", query, display);
    }

    private NaverSearchResponse search(String type, String query, int display) {
        try {
            NaverSearchResponse response = restClient.get()
                    .uri(BASE_URL + "/search/v1/{type}?query={query}&display={display}&format=json",
                            type, query, display)
                    .header("X-NCP-APIGW-API-KEY-ID", clientId)
                    .header("X-NCP-APIGW-API-KEY", clientSecret)
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(NaverSearchResponse.class);

            if (response == null) {
                log.error("[Naver API] 응답 본문이 비어 있음 type={}", type);
                throw new CustomException(ErrorCode.NAVER_SEARCH_FAILED);
            }
            return response;

        } catch (RestClientResponseException e) {
            // 응답 본문에 실패 원인이 들어 있다(예: errorCode 024 = 인증 실패).
            // 이걸 안 찍어서 401 인지 쿼터 초과인지 구분하지 못했다. 키 값 자체는 본문에 없다.
            log.error("[Naver API] 검색 실패 type={} status={} body={}",
                    type, e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.NAVER_SEARCH_FAILED);

        } catch (CustomException e) {
            throw e;

        } catch (Exception e) {
            log.error("[Naver API] 검색 실패 type={} query={}", type, query, e);
            throw new CustomException(ErrorCode.NAVER_SEARCH_FAILED);
        }
    }
}
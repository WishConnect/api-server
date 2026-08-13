package com.wishconnect.domain.insight.client;

import com.wishconnect.domain.insight.dto.NaverSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                log.warn("[Naver API] 응답이 null로 옴 type={}", type);
                return new NaverSearchResponse(List.of());
            }
            return response;

        } catch (Exception e) {
            log.error("[Naver API] 검색 실패 type={} query={}", type, query, e);
            return new NaverSearchResponse(List.of());
        }
    }
}
package com.wishconnect.domain.scholarship.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.util.StringUtils;

/*
장학금 상세페이지에서 포스터 이미지와 첨부파일 링크를 뽑는다.

공공데이터 원문에는 상세 URL·이미지·첨부파일 필드가 아예 없다(엔드포인트 69개 전수 확인).
그래서 검색으로 찾은 기관 상세페이지를 직접 읽어 채운다.

기관 사이트가 2,109곳이라 구조가 제각각이므로, 특정 사이트에 맞춘 셀렉터 대신
표준 메타태그(og:image)와 확장자 기반 규칙만 쓴다. 못 찾으면 빈 값으로 두고 넘어간다.
 */
public final class ScholarshipPageParser {

	/** 첨부파일로 볼 확장자. 공고문·신청서가 이 형태로 붙는다. */
	private static final Set<String> DOCUMENT_EXTENSIONS =
			Set.of(".hwp", ".hwpx", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".zip");

	/** 배너·로고·아이콘을 포스터로 오인하지 않도록 거른다. */
	private static final List<String> IMAGE_NOISE =
			List.of("logo", "banner", "icon", "btn", "button", "sprite", "blank", "spacer", "profile");

	private static final int MAX_DOCUMENTS = 10;

	private ScholarshipPageParser() {
	}

	/**
	 * 포스터 이미지 URL. {@code og:image} 를 우선한다 — 기관 사이트도 카카오톡 공유를 신경 쓰기 때문에
	 * 대개 채워져 있고, 본문 첫 이미지보다 정확하다. 없으면 본문에서 노이즈를 걸러 첫 이미지를 쓴다.
	 */
	public static String findPosterImageUrl(Document document) {
		String ogImage = document.select("meta[property=og:image], meta[name=og:image]").attr("abs:content");
		if (StringUtils.hasText(ogImage) && !isNoisyImage(ogImage)) {
			return ogImage;
		}
		for (Element img : document.select("img[src]")) {
			String src = img.absUrl("src");
			if (StringUtils.hasText(src) && !isNoisyImage(src)) {
				return src;
			}
		}
		return null;
	}

	/**
	 * 첨부파일 링크. 확장자로만 판단한다.
	 *
	 * <p>많은 기관 사이트가 <code>/download.do?fileId=123</code> 처럼 확장자 없는 다운로드 경로를 쓰는데,
	 * 그건 링크 텍스트에 파일명이 드러나는 경우만 잡는다. 확실하지 않은 건 넣지 않는다 —
	 * 사용자가 눌렀을 때 엉뚱한 페이지가 열리는 게 링크가 없는 것보다 나쁘다.
	 */
	public static List<Attachment> findAttachments(Document document) {
		List<Attachment> attachments = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();

		for (Element anchor : document.select("a[href]")) {
			if (attachments.size() >= MAX_DOCUMENTS) {
				break;
			}
			String href = anchor.absUrl("href");
			String text = anchor.text().trim();
			if (!StringUtils.hasText(href) || seen.contains(href)) {
				continue;
			}
			String name = resolveAttachmentName(href, text);
			if (name == null) {
				continue;
			}
			seen.add(href);
			attachments.add(new Attachment(name, href));
		}
		return attachments;
	}

	/** href 나 링크 텍스트에서 문서 확장자를 찾으면 그 이름을, 아니면 null 을 준다. */
	private static String resolveAttachmentName(String href, String text) {
		String lowerHref = href.toLowerCase(Locale.ROOT);
		for (String extension : DOCUMENT_EXTENSIONS) {
			if (lowerHref.contains(extension)) {
				return StringUtils.hasText(text) ? text : fileNameOf(href);
			}
		}
		String lowerText = text.toLowerCase(Locale.ROOT);
		for (String extension : DOCUMENT_EXTENSIONS) {
			if (lowerText.endsWith(extension)) {
				return text;
			}
		}
		return null;
	}

	private static String fileNameOf(String url) {
		String path = url.split("\\?")[0];
		int slash = path.lastIndexOf('/');
		return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
	}

	private static boolean isNoisyImage(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		return IMAGE_NOISE.stream().anyMatch(lower::contains);
	}

	public record Attachment(String name, String downloadUrl) {
	}
}

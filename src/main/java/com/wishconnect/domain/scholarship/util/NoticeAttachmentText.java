package com.wishconnect.domain.scholarship.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** 본문에서 일정을 찾지 못한 기관 공고의 같은 출처 PDF·ZIP에서 텍스트를 보완한다. */
@Slf4j
public final class NoticeAttachmentText {

	private static final int MAX_DOWNLOAD_BYTES = 4_000_000;
	private static final int MAX_ARCHIVE_BYTES = 12_000_000;
	private static final int MAX_ENTRIES = 16;
	private static final int MAX_PAGES = 8;
	private static final int MAX_TEXT_CHARS = 30_000;
	private static final int TIMEOUT_MS = 6_000;

	private NoticeAttachmentText() {
	}

	public static String scheduleEvidence(Document page, LocalDate referenceDate) {
		for (var attachment : ScholarshipPageParser.findAttachments(page).stream()
				.filter(item -> supports(item.name()) && sameOrigin(page.location(), item.downloadUrl()))
				.limit(3).toList()) {
			try {
				var response = Jsoup.connect(attachment.downloadUrl())
						.userAgent("Mozilla/5.0 (compatible; WishConnectBot/1.0)")
						.followRedirects(false).ignoreContentType(true)
						.maxBodySize(MAX_DOWNLOAD_BYTES + 1).timeout(TIMEOUT_MS).execute();
				byte[] content = response.bodyAsBytes();
				if (response.statusCode() != 200 || content.length > MAX_DOWNLOAD_BYTES) continue;
				String evidence = read(content, referenceDate);
				if (!evidence.isBlank()) return evidence;
			} catch (IOException | RuntimeException failed) {
				log.debug("[ScheduleAttachment] 공고 첨부를 읽지 못했습니다: {}", failed.getClass().getSimpleName());
			}
		}
		return "";
	}

	static String read(byte[] content, LocalDate referenceDate) throws IOException {
		if (isPdf(content)) return schedulePdf(content);
		if (content.length < 4 || content[0] != 'P' || content[1] != 'K') return "";
		try {
			return readZip(content, referenceDate, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException invalidEntryEncoding) {
			return readZip(content, referenceDate, Charset.forName("MS949"));
		}
	}

	private static String readZip(byte[] content, LocalDate referenceDate, Charset charset) throws IOException {
		int remaining = MAX_ARCHIVE_BYTES;
		String selected = "";
		int bestCount = 0;
		boolean conflicting = false;
		try (var archive = new ZipInputStream(new ByteArrayInputStream(content), charset)) {
			for (int i = 0; i < MAX_ENTRIES; i++) {
				var entry = archive.getNextEntry();
				if (entry == null) break;
				byte[] data = archive.readNBytes(remaining + 1);
				remaining -= data.length;
				if (remaining < 0) return "";
				if (!entry.getName().toLowerCase(Locale.ROOT).endsWith(".pdf") || !isPdf(data)) continue;
				String evidence = schedulePdf(data);
				int count = NoticeTimelineParser.withSourceEvidence(null, evidence, referenceDate).size();
				if (count > bestCount) {
					selected = evidence;
					bestCount = count;
					conflicting = false;
				} else if (count > 0 && count == bestCount && !selected.equals(evidence)) {
					conflicting = true;
				}
			}
		}
		return conflicting ? "" : selected;
	}

	private static String schedulePdf(byte[] content) {
		try (var pdf = Loader.loadPDF(content)) {
			var stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			stripper.setEndPage(MAX_PAGES);
			String text = stripper.getText(pdf).replace("\0", "");
			return text.substring(0, Math.min(text.length(), MAX_TEXT_CHARS)).lines()
					.map(String::strip).filter(line -> !line.isEmpty())
					.filter(line -> NoticeTimelineParser.hasScheduleLabel(line)
							|| TemporalCandidateParser.hasApplicationLabel(line))
					.collect(java.util.stream.Collectors.joining("\n"));
		} catch (IOException | RuntimeException invalid) {
			return "";
		}
	}

	private static boolean isPdf(byte[] content) {
		return content.length >= 5 && content[0] == '%' && content[1] == 'P'
				&& content[2] == 'D' && content[3] == 'F' && content[4] == '-';
	}

	private static boolean supports(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".pdf") || lower.endsWith(".zip");
	}

	private static boolean sameOrigin(String page, String download) {
		try {
			URI source = URI.create(page);
			URI target = URI.create(download);
			return "https".equals(source.getScheme()) && "https".equals(target.getScheme())
					&& source.getHost() != null && source.getHost().equalsIgnoreCase(target.getHost())
					&& source.getPort() == target.getPort() && target.getUserInfo() == null;
		} catch (IllegalArgumentException invalidUrl) {
			return false;
		}
	}
}

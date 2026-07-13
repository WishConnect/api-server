package com.wishconnect.domain.auth.service;

import com.wishconnect.domain.auth.config.MailProperties;
import com.wishconnect.global.exception.CustomException;
import com.wishconnect.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 이메일 발송 (Spring {@link JavaMailSender}, 발송 구현체는 AWS SES SMTP).
 * 인증 코드 등 민감정보는 로그에 남기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

	private final JavaMailSender mailSender;
	private final MailProperties mailProperties;

	public void sendVerificationCode(String to, String code) {
		send(to, "[WishConnect] 이메일 인증 코드",
				"아래 인증 코드를 5분 이내에 입력해주세요.\n\n인증 코드: " + code);
	}

	public void sendPasswordResetCode(String to, String code) {
		send(to, "[WishConnect] 비밀번호 재설정 코드",
				"아래 재설정 코드를 5분 이내에 입력해주세요.\n\n재설정 코드: " + code);
	}

	private void send(String to, String subject, String text) {
		String masked = maskEmail(to);
		log.info("[Mail] 발송 시작 to={}", masked);
		try {
			SimpleMailMessage message = new SimpleMailMessage();
			message.setFrom(mailProperties.from());
			message.setTo(to);
			message.setSubject(subject);
			message.setText(text);
			mailSender.send(message);
			log.info("[Mail] 발송 성공 to={}", masked);
		} catch (MailException e) {
			// SES 샌드박스/미검증 발신 등으로 실패할 수 있으므로 명확히 로깅
			log.error("[Mail] 발송 실패 to={} : {}", masked, e.getMessage());
			throw new CustomException(ErrorCode.EMAIL_SEND_FAILED);
		}
	}

	private String maskEmail(String email) {
		int at = email.indexOf('@');
		if (at <= 1) {
			return "***" + (at >= 0 ? email.substring(at) : "");
		}
		return email.charAt(0) + "***" + email.substring(at);
	}
}

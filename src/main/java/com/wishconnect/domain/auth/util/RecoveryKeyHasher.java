package com.wishconnect.domain.auth.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Redis 키에 이메일·이름·토큰 원문을 남기지 않도록 SHA-256 식별자로 바꾼다. */
public final class RecoveryKeyHasher {

	private RecoveryKeyHasher() {
	}

	public static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}
}

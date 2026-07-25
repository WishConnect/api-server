package com.wishconnect.domain.notification.entity;

import com.wishconnect.domain.user.entity.User;
import com.wishconnect.global.common.BaseCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(name = "notification")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Notification extends BaseCreatedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationType type;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String content;

	/** 연관 대상 종류(예: SCHOLARSHIP). 다형이라 FK 미매핑 */
	@Column
	private String relatedType;

	@Column
	private Long relatedId;

	@Column(nullable = false)
	private boolean isRead;

	public static Notification create(User user, NotificationType type, String title, String content,
			String relatedType, Long relatedId) {
		return Notification.builder()
				.user(user)
				.type(type)
				.title(title)
				.content(content)
				.relatedType(relatedType)
				.relatedId(relatedId)
				.isRead(false)
				.build();
	}

	public void markAsRead() {
		this.isRead = true;
	}
}

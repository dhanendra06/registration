package io.mosip.registration.processor.core.anonymous.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Data;

@Data
public class BiometricInfoDTO {

	private String type;
	private String subType;
	private Long qualityScore;
	private String attempts;

	/**
	 * Device digital ID. Stored as the raw JSON string of the digital ID payload.
	 * The {@link JsonRawValue} annotation tells Jackson to write this value AS-IS
	 * during serialisation — without wrapping it in quotes or escaping inner quotes —
	 * so the anonymous-profile output contains {@code digitalId} as a nested JSON
	 * object instead of a double-encoded / unescaped-quotes string.
	 * <p>The setter signature is unchanged (still accepts a String), so existing
	 * callers like {@code AnonymousProfileServiceImpl} continue to work unmodified.
	 */
	@JsonRawValue
	private String digitalId;
}

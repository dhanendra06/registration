package io.mosip.registration.processor.stages.cmdvalidator;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import io.mosip.kernel.core.util.DateUtils2;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.processor.core.packet.dto.AdditionalInfoRequestDto;
import io.mosip.registration.processor.packet.storage.exception.ObjectDoesnotExistsException;
import io.mosip.registration.processor.packet.storage.utils.OSIUtils;
import io.mosip.registration.processor.status.service.AdditionalInfoRequestService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.biometrics.entities.BIR;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.core.exception.BaseCheckedException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.CryptoUtil;
import io.mosip.kernel.core.util.StringUtils;
import io.mosip.kernel.signature.constant.SignatureConstant;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.constant.JsonConstant;
import io.mosip.registration.processor.core.constant.MappingJsonConstants;
import io.mosip.registration.processor.core.constant.ProviderStageName;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.http.RequestWrapper;
import io.mosip.registration.processor.core.http.ResponseWrapper;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.FieldValue;
import io.mosip.registration.processor.core.packet.dto.HotlistRequestResponseDTO;
import io.mosip.registration.processor.core.packet.dto.JWTSignatureVerifyRequestDto;
import io.mosip.registration.processor.core.packet.dto.JWTSignatureVerifyResponseDto;
import io.mosip.registration.processor.core.packet.dto.NewDigitalId;
import io.mosip.registration.processor.core.packet.dto.RegOsiDto;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;

@Service
public class DeviceValidator {

	private static final Logger regProcLogger = RegProcessorLogger.getLogger(DeviceValidator.class);

	private static final String DATETIME_PATTERN_KEY = "mosip.registration.processor.datetime.pattern";
	private static final String VALID = "Valid";

	// Predefined list – no need to construct every call
	private static final List<String> BIOMETRIC_FIELDS = Arrays.asList(
			MappingJsonConstants.INDIVIDUAL_BIOMETRICS,
			MappingJsonConstants.AUTHENTICATION_BIOMETRICS,
			MappingJsonConstants.INTRODUCER_BIO,
			MappingJsonConstants.OFFICERBIOMETRICFILENAME,
			MappingJsonConstants.SUPERVISORBIOMETRICFILENAME
	);

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private RegistrationProcessorRestClientService<Object> registrationProcessorRestService;

	@Autowired
	private Environment env;

	@Autowired
	private OSIUtils osiUtils;

	@Autowired
	private AdditionalInfoRequestService additionalInfoRequestService;

	@Autowired
	private PriorityBasedPacketManagerService packetManagerService;

	@Value("${mosip.regproc.cmd-validator.device.disable-trust-validation:false}")
	private Boolean disableTrustValidation;

	@Value("${mosip.regproc.cmd-validator.device.allowed-digital-id-timestamp-variation:5}")
	private int allowedDigitalIdTimestampVariation;

	@Value("${mosip.regproc.cmd-validator.device.digital-id-timestamp-format:yyyy-MM-dd'T'HH:mm:ss'Z'}")
	private String digitalIdTimestampFormat;

	@Value("#{T(java.util.Arrays).asList('${mosip.regproc.common.before-cbeff-others-attibute.reg-client-versions:}')}")
	private List<String> regClientVersionsBeforeCbeffOthersAttritube;

	@Value("#{T(java.util.Arrays).asList('${mosip.regproc.biometric.correction.process:}')}")
	private List<String> biometricCorrectionProcess;

	// Cached formatters for better performance
	private volatile DateTimeFormatter packetCreationFormatter;
	private volatile DateTimeFormatter digitalIdFormatter;

	private DateTimeFormatter getPacketCreationFormatter() {
		if (packetCreationFormatter == null) {
			synchronized (this) {
				if (packetCreationFormatter == null) {
					String pattern = env.getProperty(DATETIME_PATTERN_KEY);
					packetCreationFormatter = DateTimeFormatter.ofPattern(pattern);
				}
			}
		}
		return packetCreationFormatter;
	}

	private DateTimeFormatter getDigitalIdFormatter() {
		if (digitalIdFormatter == null) {
			synchronized (this) {
				if (digitalIdFormatter == null) {
					digitalIdFormatter = DateTimeFormatter.ofPattern(digitalIdTimestampFormat);
				}
			}
		}
		return digitalIdFormatter;
	}

	/**
	 * Validates devices for all relevant biometrics attached to the packet.
	 */
	public void validate(RegOsiDto regOsi, String process, String registrationId)
			throws  IOException, BaseCheckedException, JSONException {

		for (String field : BIOMETRIC_FIELDS) {
			if (MappingJsonConstants.OFFICERBIOMETRICFILENAME.equals(field)
					|| MappingJsonConstants.SUPERVISORBIOMETRICFILENAME.equals(field)) {

				String value = getOperationsDataFromMetaInfo(registrationId, process, field);
				if (value != null && !value.isEmpty()) {
					BiometricRecord biometricRecord = packetManagerService.getBiometrics(
							registrationId, field, process, ProviderStageName.CMD_VALIDATOR);
					if (biometricRecord == null) {
						throw new BaseCheckedException(
								StatusUtil.DEVICE_VALIDATION_FAILED.getCode(),
								StatusUtil.DEVICE_VALIDATION_FAILED.getMessage()
										+ " --> Biometrics not found for field " + field);
					}
					validateDevicesInBiometricRecord(biometricRecord, regOsi, registrationId);
				}
			} else {
				String value = packetManagerService.getField(
						registrationId, field, process, ProviderStageName.PACKET_VALIDATOR);
				if (value != null && !value.isEmpty()) {
					BiometricRecord biometricRecord = packetManagerService.getBiometricsByMappingJsonKey(
							registrationId, field, process, ProviderStageName.CMD_VALIDATOR);
					if (biometricRecord == null) {
						throw new BaseCheckedException(
								StatusUtil.DEVICE_VALIDATION_FAILED.getCode(),
								StatusUtil.DEVICE_VALIDATION_FAILED.getMessage()
										+ " --> Biometrics not found for field " + field);
					}
					validateDevicesInBiometricRecord(biometricRecord, regOsi, registrationId);
				}
			}
		}
	}

	private String getOperationsDataFromMetaInfo(String id, String process, String fileName)
			throws ApisResourceAccessException, PacketManagerException, IOException,
			JSONException, JsonParseException, JsonMappingException,
			JsonProcessingException, io.mosip.kernel.core.util.exception.JsonProcessingException {

		Map<String, String> metaInfoMap = packetManagerService.getMetaInfo(
				id, process, ProviderStageName.PACKET_VALIDATOR);

		String metadata = metaInfoMap.get(JsonConstant.OPERATIONSDATA);
		if (StringUtils.isEmpty(metadata)) {
			return null;
		}

		JSONArray jsonArray = new JSONArray(metadata);
		for (int i = 0, len = jsonArray.length(); i < len; i++) {
			if (!jsonArray.isNull(i)) {
				JSONObject jsonObject = jsonArray.getJSONObject(i);
				FieldValue fieldValue = mapper.readValue(jsonObject.toString(), FieldValue.class);
				if (fileName.equalsIgnoreCase(fieldValue.getLabel())) {
					return fieldValue.getValue();
				}
			}
		}
		return null;
	}

	private void validateDevicesInBiometricRecord(BiometricRecord biometricRecord,
												  RegOsiDto regOsi,
												  String rid)
			throws IOException, BaseCheckedException, JSONException {

		List<BIR> birs = biometricRecord.getSegments();
		if (birs == null || birs.isEmpty()) {
			return; // nothing to validate
		}

		List<JSONObject> payloads = new ArrayList<>();
		for (BIR bir : birs) {
			Map<String, String> others = bir.getOthers();
			if (MapUtils.isNotEmpty(others)) {
				boolean exception = "true".equals(others.get("EXCEPTION"));
				if (exception) {
					continue;
				}
				String payload = others.get("PAYLOAD");
				if (payload != null) {
					payloads.add(new JSONObject(payload));
				}
			} else if (!regClientVersionsBeforeCbeffOthersAttritube.contains(regOsi.getRegClientVersion())) {
				throw new BaseCheckedException(
						StatusUtil.DEVICE_VALIDATION_FAILED.getCode(),
						StatusUtil.DEVICE_VALIDATION_FAILED.getMessage()
								+ "-->Others info is not prsent in packet");
			}
		}

		Set<String> validatedDigitalIds = new HashSet<>();
		Set<String> deviceCodeTimestamps = new HashSet<>();

		for (JSONObject payload : payloads) {
			// Decode digitalId JWT payload (2nd part)
			String jwt = payload.getString("digitalId");
			String[] jwtParts = jwt.split("\\.");
			String midPart = jwtParts.length > 1 ? jwtParts[1] : "";

			String digitalIdString;
			try {
				digitalIdString = new String(CryptoUtil.decodeURLSafeBase64(midPart));
			} catch (IllegalArgumentException ex) {
				digitalIdString = new String(CryptoUtil.decodePlainBase64(midPart));
			}

			NewDigitalId newDigitalId = mapper.readValue(digitalIdString, NewDigitalId.class);

			// Validate signature/trust only once per unique digitalId payload
			if (validatedDigitalIds.add(digitalIdString)) {
				validateDigitalId(payload);
			}

			// Timestamp validations
			String digitalIdDateTime = newDigitalId.getDateTime();
			validateTimestamp(rid, regOsi.getPacketCreationDate(), digitalIdDateTime);
			validateTimestamp(rid, regOsi.getPacketCreationDate(), payload.getString("timestamp"));

			// Construct deviceId (serialNo + make + model)
			String deviceId = newDigitalId.getSerialNo()
					+ newDigitalId.getMake()
					+ newDigitalId.getModel();

			String deviceKey = deviceId + digitalIdDateTime;
			if (deviceCodeTimestamps.add(deviceKey)) {
				validateDeviceForHotlist(deviceId, digitalIdDateTime);
			}
		}
	}

	/**
	 * This method is added to support reprocessing of main-process packet from beginning.
	 * It fetches the packet creation date of the latest relevant correction packet.
	 */
	private String getCorrectionPacketDateTime(String rid)
			throws ApisResourceAccessException, IOException, PacketManagerException,
			io.mosip.kernel.core.util.exception.JsonProcessingException, JSONException {

		String process = getCorrectionPacketProcess(rid);
		if (process == null) {
			return null;
		}

		try {
			Map<String, String> metaInfo = packetManagerService.getMetaInfo(
					rid, process, ProviderStageName.CMD_VALIDATOR);
			RegOsiDto regOsi = osiUtils.getOSIDetailsFromMetaInfo(metaInfo);
			return regOsi.getPacketCreationDate();
		} catch (ObjectDoesnotExistsException e) {
			return null;
		}
	}

	private String getCorrectionPacketProcess(String rid) {
		List<AdditionalInfoRequestDto> additionalInfos =
				additionalInfoRequestService.getAdditionalInfoByRid(rid);

		if (CollectionUtils.isEmpty(additionalInfos)) {
			return null;
		}

		// Sort: latest first
		additionalInfos.sort(Comparator.comparing(AdditionalInfoRequestDto::getTimestamp).reversed());

		List<AdditionalInfoRequestDto> candidates = additionalInfos;
		if (additionalInfos.size() > 1 && CollectionUtils.isNotEmpty(biometricCorrectionProcess)) {
			List<AdditionalInfoRequestDto> filtered = additionalInfos.stream()
					.filter(add -> biometricCorrectionProcess.contains(add.getAdditionalInfoProcess()))
					.collect(Collectors.toList());
			if (CollectionUtils.isNotEmpty(filtered)) {
				candidates = filtered;
			}
		}

		return candidates.iterator().next().getAdditionalInfoProcess();
	}

	private void validateDeviceForHotlist(String deviceCode, String digitalIdTimestamp)
			throws JsonParseException, JsonMappingException, JsonProcessingException,
			IOException, JSONException, BaseCheckedException {

		List<String> pathSegments = new ArrayList<>(2);
		pathSegments.add("DEVICE");
		pathSegments.add(deviceCode);

		ResponseWrapper<?> responseWrapper = (ResponseWrapper<?>) registrationProcessorRestService
				.getApi(ApiName.DEVICEHOTLIST, pathSegments, "", "", ResponseWrapper.class);

		if (responseWrapper.getResponse() == null) {
			throw new BaseCheckedException(
					responseWrapper.getErrors().get(0).getErrorCode(),
					responseWrapper.getErrors().get(0).getMessage());
		}

		HotlistRequestResponseDTO hotListResponse = mapper.readValue(
				mapper.writeValueAsString(responseWrapper.getResponse()),
				HotlistRequestResponseDTO.class);

		DateTimeFormatter format = getDigitalIdFormatter();
		LocalDateTime payloadTime = LocalDateTime.parse(digitalIdTimestamp, format);

		if ("BLOCKED".equalsIgnoreCase(hotListResponse.getStatus())) {
			LocalDateTime expiry = hotListResponse.getExpiryTimestamp();
			if (expiry != null) {
				if (payloadTime.isBefore(expiry)) {
					throw new BaseCheckedException(
							StatusUtil.DEVICE_HOTLISTED.getCode(),
							StatusUtil.DEVICE_HOTLISTED.getMessage());
				}
			} else {
				throw new BaseCheckedException(
						StatusUtil.DEVICE_HOTLISTED.getCode(),
						StatusUtil.DEVICE_HOTLISTED.getMessage());
			}
		}
	}

	private void validateTimestamp(String rid, String packetCreationDate, String dateTime)
			throws BaseCheckedException, IOException, JSONException {

		DateTimeFormatter packetFormatter = getPacketCreationFormatter();
		DateTimeFormatter digitalFormatter = getDigitalIdFormatter();

		LocalDateTime packetCreationDateTime =
				LocalDateTime.parse(packetCreationDate, packetFormatter);
		LocalDateTime timestamp =
				LocalDateTime.parse(dateTime, digitalFormatter);

		boolean outsideWindow =
				timestamp.isAfter(packetCreationDateTime)
						|| timestamp.isBefore(
						packetCreationDateTime.minus(allowedDigitalIdTimestampVariation, ChronoUnit.MINUTES));

		if (!outsideWindow) {
			return;
		}

		String correctionPacketCreationTime = getCorrectionPacketDateTime(rid);
		if (validateCorrectionTimestamp(correctionPacketCreationTime, dateTime)) {
			throw new BaseCheckedException(
					StatusUtil.TIMESTAMP_NOT_VALID.getCode(),
					StatusUtil.TIMESTAMP_NOT_VALID.getMessage());
		}
	}

	private boolean validateCorrectionTimestamp(String correctionPacketCreationTime, String dateTime) {
		if (correctionPacketCreationTime == null) {
			return true; // original behavior: treat as invalid outside window
		}

		DateTimeFormatter packetFormatter = getPacketCreationFormatter();
		DateTimeFormatter digitalFormatter = getDigitalIdFormatter();

		LocalDateTime packetCreationDateTime =
				LocalDateTime.parse(correctionPacketCreationTime, packetFormatter);
		LocalDateTime timestamp =
				LocalDateTime.parse(dateTime, digitalFormatter);

		return timestamp.isAfter(packetCreationDateTime)
				|| timestamp.isBefore(
				packetCreationDateTime.minus(allowedDigitalIdTimestampVariation, ChronoUnit.MINUTES));
	}

	private void validateDigitalId(JSONObject payload)
			throws JsonParseException, JsonMappingException, JsonProcessingException,
			IOException, JSONException, BaseCheckedException {

		JWTSignatureVerifyRequestDto jwtSignatureVerifyRequestDto = new JWTSignatureVerifyRequestDto();
		jwtSignatureVerifyRequestDto.setApplicationId("REGISTRATION");
		jwtSignatureVerifyRequestDto.setReferenceId("SIGN");
		jwtSignatureVerifyRequestDto.setJwtSignatureData(payload.getString("digitalId"));
		jwtSignatureVerifyRequestDto.setActualData(payload.getString("digitalId").split("\\.")[1]);
		jwtSignatureVerifyRequestDto.setValidateTrust(!disableTrustValidation);
		jwtSignatureVerifyRequestDto.setDomain("Device");

		RequestWrapper<JWTSignatureVerifyRequestDto> request = new RequestWrapper<>();
		request.setRequest(jwtSignatureVerifyRequestDto);
		request.setVersion("1.0");

		DateTimeFormatter format = getPacketCreationFormatter();
		LocalDateTime now = LocalDateTime.parse(
				DateUtils2.getUTCCurrentDateTimeString(env.getProperty(DATETIME_PATTERN_KEY)),
				format);
		request.setRequesttime(now);

		ResponseWrapper<?> responseWrapper = (ResponseWrapper<?>) registrationProcessorRestService
				.postApi(ApiName.JWTVERIFY, "", "", request, ResponseWrapper.class);

		if (responseWrapper.getResponse() == null) {
			throw new BaseCheckedException(
					responseWrapper.getErrors().get(0).getErrorCode(),
					responseWrapper.getErrors().get(0).getMessage());
		}

		JWTSignatureVerifyResponseDto jwtResponse = mapper.readValue(
				mapper.writeValueAsString(responseWrapper.getResponse()),
				JWTSignatureVerifyResponseDto.class);

		if (!jwtResponse.isSignatureValid()) {
			throw new BaseCheckedException(
					StatusUtil.DEVICE_SIGNATURE_VALIDATION_FAILED.getCode(),
					StatusUtil.DEVICE_SIGNATURE_VALIDATION_FAILED.getMessage());
		}

		if (!disableTrustValidation
				&& !SignatureConstant.TRUST_VALID.equals(jwtResponse.getTrustValid())) {
			throw new BaseCheckedException(
					StatusUtil.DEVICE_SIGNATURE_VALIDATION_FAILED.getCode(),
					StatusUtil.DEVICE_SIGNATURE_VALIDATION_FAILED.getMessage()
							+ "-->" + jwtResponse.getTrustValid());
		}
	}
}

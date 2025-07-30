package io.mosip.registration.processor.stages.demodedupe;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.mosip.kernel.core.fsadapter.exception.FSAdapterException;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.code.AbisStatusCode;
import io.mosip.registration.processor.core.code.DedupeSourceName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionTypeCode;
import io.mosip.registration.processor.core.constant.AbisConstant;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.MappingJsonConstants;
import io.mosip.registration.processor.core.constant.ProviderStageName;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketDecryptionFailureException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.exception.RegistrationProcessorCheckedException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.Identity;
import io.mosip.registration.processor.core.packet.dto.abis.AbisResponseDetDto;
import io.mosip.registration.processor.core.packet.dto.abis.AbisResponseDto;
import io.mosip.registration.processor.core.packet.dto.abis.RegDemoDedupeListDto;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.DemographicInfoDto;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.IndividualDemographicDedupe;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.JsonValue;
import io.mosip.registration.processor.core.spi.packetmanager.PacketInfoManager;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.status.util.TrimExceptionMessage;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.storage.dto.ApplicantInfoDto;
import io.mosip.registration.processor.packet.storage.utils.ABISHandlerUtil;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.packet.storage.utils.Utility;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.stages.app.constants.DemoDedupeConstants;
import io.mosip.registration.processor.stages.dto.DemoDedupeStatusDTO;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dao.RegistrationStatusDao;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.entity.RegistrationStatusEntity;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class DemodedupeProcessor.
 */
@Service
@Transactional
public class DemodedupeProcessor {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(DemodedupeProcessor.class);
	private static final String MANUAL_VERIFICATION_STATUS = "PENDING";

	/** The registration status service. */
	@Autowired
	private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	/** The demo dedupe. */
	@Autowired
	private DemoDedupe demoDedupe;

	/** The packet info manager. */
	@Autowired
	private PacketInfoManager<Identity, ApplicantInfoDto> packetInfoManager;

	/** The registration exception mapper util. */
	private RegistrationExceptionMapperUtil registrationExceptionMapperUtil = new RegistrationExceptionMapperUtil();

	/** The utility. */
	@Autowired
	private Utilities utilities;

	/** The registration status dao. */
	@Autowired
	private RegistrationStatusDao registrationStatusDao;

	/** The abis handler util. */
	@Autowired
	private ABISHandlerUtil abisHandlerUtil;

	@Autowired
	private Environment env;

	@Autowired
	private Utility utility;

	/** The is match found. */
	private volatile boolean isMatchFound = false;

	private static final String DEMODEDUPEENABLE = "mosip.registration.processor.demographic.deduplication.enable";

	private static final String TRUE = "true";

	private static final String GLOBAL_CONFIG_TRUE_VALUE = "Y";

	/** The age limit. */
	@Value("${mosip.kernel.applicant.type.age.limit}")
	private String ageLimit;

	@Value("${registration.processor.infant.dedupe}")
	private String infantDedupe;

	@Value("${registration.processor.demodedupe.manual.adjudication.status}")
	private String manualVerificationStatus;

	/**
	 * Process.
	 *
	 * @param object    the object
	 * @param stageName the stage name
	 * @return the message DTO
	 */
	public MessageDTO process(MessageDTO object, String stageName) {
		String registrationId = object.getRid();

		regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
				registrationId, "DemoDedupeStage::DemoDedupeProcessor::entry");

		object.setMessageBusAddress(MessageBusAddress.DEMO_DEDUPE_BUS_IN);
		object.setInternalError(Boolean.FALSE);
		object.setIsValid(Boolean.FALSE);
		isMatchFound = false;

		/** The duplicate dtos. */
		List<DemographicInfoDto> duplicateDtos = new ArrayList<>();

		boolean isTransactionSuccessful = false;
		boolean isDemoDedupeSkip = true;
		String moduleName = ModuleName.DEMO_DEDUPE.toString();
		String moduleId = PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode();
		TrimExceptionMessage trimExceptionMessage = new TrimExceptionMessage();
		boolean isDuplicateRequestForSameTransactionId = false;
		LogDescription description = new LogDescription();

		InternalRegistrationStatusDto registrationStatusDto = registrationStatusService.getRegistrationStatus(
				registrationId, object.getReg_type(), object.getIteration(), object.getWorkflowInstanceId());

		try {
			String regType = registrationStatusDto.getRegistrationType();

			IndividualDemographicDedupe demographicData = packetInfoManager.getIdentityKeysAndFetchValuesFromJSON(
					registrationId, regType, ProviderStageName.DEMO_DEDUPE);

			JSONObject regProcessorIdentityJson = utilities
					.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);
			String uinFieldCheck = utility.getUIn(registrationId, regType,
					ProviderStageName.DEMO_DEDUPE);

			JSONObject jsonObject = utilities.retrieveIdrepoJson(uinFieldCheck);
			if (jsonObject == null) {
				DemoDedupeStatusDTO demoDedupeStatusDTO = insertDemodedupDetailsAndPerformDedup(demographicData,
						registrationStatusDto, duplicateDtos, object, moduleId, moduleName, isDemoDedupeSkip,
						description);
				isTransactionSuccessful = demoDedupeStatusDTO.isTransactionSuccessful();
				duplicateDtos = demoDedupeStatusDTO.getDuplicateDtos() != null
						? demoDedupeStatusDTO.getDuplicateDtos() : new ArrayList<>();
			} else {
				insertDemodedupDetails(demographicData, regProcessorIdentityJson, jsonObject, registrationStatusDto,
						object, moduleId, moduleName);
			}
			
			if (abisHandlerUtil.getPacketStatus(registrationStatusDto)
					.equalsIgnoreCase(AbisConstant.DUPLICATE_FOR_SAME_TRANSACTION_ID))
				isDuplicateRequestForSameTransactionId = true;

			registrationStatusDto.setRegistrationStageName(stageName);
			if (isTransactionSuccessful) {
				object.setIsValid(Boolean.TRUE);
			}

		} catch (FSAdapterException e) {
			handleError(object, registrationStatusDto, description,
	                RegistrationStatusCode.PROCESSING, StatusUtil.OBJECT_STORE_EXCEPTION,
	                PlatformErrorMessages.PACKET_DEMO_PACKET_STORE_NOT_ACCESSIBLE, e);
		} catch (IllegalArgumentException e) {
			handleError(object, registrationStatusDto, description,
	                RegistrationStatusCode.FAILED, StatusUtil.IIEGAL_ARGUMENT_EXCEPTION,
	                PlatformErrorMessages.PACKET_DEMO_DEDUPE_FAILED, e);
		} catch (ApisResourceAccessException e) {
			handleError(object, registrationStatusDto, description,
	                RegistrationStatusCode.PROCESSING, StatusUtil.API_RESOUCE_ACCESS_FAILED,
	                PlatformErrorMessages.RPR_DEMO_API_RESOUCE_ACCESS_FAILED, e);
		} catch (Exception ex) {
			 handleError(object, registrationStatusDto, description,
		                RegistrationStatusCode.FAILED, StatusUtil.UNKNOWN_EXCEPTION_OCCURED,
		                PlatformErrorMessages.PACKET_DEMO_DEDUPE_FAILED, ex);
		} finally {
			if (!isDuplicateRequestForSameTransactionId) {
				registrationStatusDto.setLatestTransactionTypeCode(
						RegistrationTransactionTypeCode.DEMOGRAPHIC_VERIFICATION.toString());

				moduleId = isTransactionSuccessful ? PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode()
						: description.getCode();

				registrationStatusService.updateRegistrationStatus(registrationStatusDto, moduleId, moduleName);
				try {
					if (isMatchFound) {
						saveDuplicateDtoList(duplicateDtos, registrationStatusDto, object);
					}
				} catch (Exception e) {
					registrationStatusDto.setRegistrationStageName(stageName);
					registrationStatusDto
							.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.REPROCESS.toString());
					registrationStatusDto.setStatusComment(trimExceptionMessage
							.trimExceptionMessage(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getMessage() + e.getMessage()));
					registrationStatusDto.setSubStatusCode(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getCode());
					description.setMessage(DemoDedupeConstants.NO_DATA_IN_DEMO);
					registrationStatusService.updateRegistrationStatus(registrationStatusDto, moduleId, moduleName);

					regProcLogger.error(DemoDedupeConstants.NO_DATA_IN_DEMO, "", "", ExceptionUtils.getStackTrace(e));
					object.setMessageBusAddress(MessageBusAddress.DEMO_DEDUPE_BUS_IN);
					object.setInternalError(Boolean.TRUE);
				}

				if (object.getInternalError()) {
					updateErrorFlags(registrationStatusDto, object);
				}
				regProcLogger.info(LoggerFileConstant.SESSIONID.name(), LoggerFileConstant.REGISTRATIONID.name(),
						registrationId, object.getIsValid() && !object.getInternalError()
								? "DemoDedupeProcessor::success" : "DemoDedupeProcessor::failure");

				String eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
				String eventName = isTransactionSuccessful ? EventName.UPDATE.toString()
						: EventName.EXCEPTION.toString();
				String eventType = isTransactionSuccessful ? EventType.BUSINESS.toString()
						: EventType.SYSTEM.toString();

				auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName,
						eventType, moduleId, moduleName, registrationId);
			} else {
				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						"Duplicate request received for same latest transaction id. This will be ignored.");
				object.setIsValid(false);
				object.setInternalError(true);
			}
		}

		return object;
	}

	/**
	 * Handles all error cases with common code.
	 */
	private void handleError(MessageDTO object, InternalRegistrationStatusDto statusDto, LogDescription description,
	                         RegistrationStatusCode statusCode, StatusUtil statusUtil,
	                         PlatformErrorMessages platformMessage, Exception e) {
	    statusDto.setStatusCode(statusCode.name());
	    statusDto.setStatusComment(new TrimExceptionMessage()
	            .trimExceptionMessage(statusUtil.getMessage() + e.getMessage()));
	    statusDto.setSubStatusCode(statusUtil.getCode());
	    statusDto.setLatestTransactionStatusCode(
	            registrationExceptionMapperUtil.getStatusCode(
	                    RegistrationExceptionTypeCode.valueOf(statusCode.name())));
	    description.setCode(platformMessage.getCode());
	    description.setMessage(platformMessage.getMessage());
	    regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), description.getCode() + " -- " + LoggerFileConstant.REGISTRATIONID.toString(),
	            "", description.getMessage() + "\n" + ExceptionUtils.getStackTrace(e));
	    object.setInternalError(true);
	}
	
	private void insertDemodedupDetails(IndividualDemographicDedupe demographicData,
			JSONObject regProcessorIdentityJson, JSONObject jsonObject,
			InternalRegistrationStatusDto registrationStatusDto, MessageDTO object, String moduleId,
			String moduleName) {
		
		IndividualDemographicDedupe demoDedupeData = new IndividualDemographicDedupe();
		if (demographicData.getName() == null || demographicData.getName().isEmpty()) {
			String names = JsonUtil.getJSONValue(
					JsonUtil.getJSONObject(regProcessorIdentityJson, MappingJsonConstants.NAME),
					MappingJsonConstants.VALUE);

			if (names != null && !names.isEmpty()) {
				List<JsonValue[]> jsonValueList = Arrays.stream(names.split(","))
						.map(name -> JsonUtil.getJsonValues(jsonObject, name))
						.filter(Objects::nonNull)
						.toList();
				demoDedupeData.setName(jsonValueList.isEmpty() ? null : jsonValueList);
			}
		}else {
			demoDedupeData.setName(demographicData.getName());
		}

		// Cache JSON mapping values to avoid multiple lookups
	    String dobKey = JsonUtil.getJSONValue(
	            JsonUtil.getJSONObject(regProcessorIdentityJson, MappingJsonConstants.DOB),
	            MappingJsonConstants.VALUE);
	    String genderKey = JsonUtil.getJSONValue(
	            JsonUtil.getJSONObject(regProcessorIdentityJson, MappingJsonConstants.GENDER),
	            MappingJsonConstants.VALUE);
	    String phoneKey = JsonUtil.getJSONValue(
	            JsonUtil.getJSONObject(regProcessorIdentityJson, MappingJsonConstants.PHONE),
	            MappingJsonConstants.VALUE);
	    String emailKey = JsonUtil.getJSONValue(
	            JsonUtil.getJSONObject(regProcessorIdentityJson, MappingJsonConstants.EMAIL),
	            MappingJsonConstants.VALUE);

		// Populate missing demographic fields
	    demoDedupeData.setDateOfBirth(
	            demographicData.getDateOfBirth() != null ? demographicData.getDateOfBirth() : JsonUtil.getJSONValue(jsonObject, dobKey));
	    demoDedupeData.setGender(
	            demographicData.getGender() != null ? demographicData.getGender() : JsonUtil.getJsonValues(jsonObject, genderKey));
	    demoDedupeData.setPhone(
	            demographicData.getPhone() != null ? demographicData.getPhone() : JsonUtil.getJSONValue(jsonObject, phoneKey));
	    demoDedupeData.setEmail(
	            demographicData.getEmail() != null ? demographicData.getEmail() : JsonUtil.getJSONValue(jsonObject, emailKey));

		// Save demographic dedupe details
		packetInfoManager.saveIndividualDemographicDedupeUpdatePacket(demoDedupeData,
				registrationStatusDto.getRegistrationId(), moduleId, registrationStatusDto.getRegistrationType(),
				moduleName, registrationStatusDto.getIteration(), registrationStatusDto.getWorkflowInstanceId());

		object.setIsValid(Boolean.TRUE);
		registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
		registrationStatusDto.setStatusComment(StatusUtil.DEMO_DEDUPE_SKIPPED.getMessage());
		registrationStatusDto.setSubStatusCode(StatusUtil.DEMO_DEDUPE_SKIPPED.getCode());
		registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
	}

	private DemoDedupeStatusDTO insertDemodedupDetailsAndPerformDedup(IndividualDemographicDedupe demographicData,
			InternalRegistrationStatusDto registrationStatusDto, List<DemographicInfoDto> duplicateDtos,
			MessageDTO object, String moduleId, String moduleName, boolean isDemoDedupeSkip, LogDescription description)
			throws ApisResourceAccessException, JsonProcessingException, PacketManagerException, IOException,
			PacketDecryptionFailureException, io.mosip.kernel.core.exception.IOException,
			RegistrationProcessorCheckedException {
		
		DemoDedupeStatusDTO demoDedupeStatusDTO = new DemoDedupeStatusDTO();
		boolean isTransactionSuccessful = false;
		String registrationId = registrationStatusDto.getRegistrationId();

		String packetStatus = abisHandlerUtil.getPacketStatus(registrationStatusDto);
		if (packetStatus.equalsIgnoreCase(AbisConstant.PRE_ABIS_IDENTIFICATION)) {
			// Save demographic dedupe data
			packetInfoManager.saveIndividualDemographicDedupeUpdatePacket(demographicData, registrationId, moduleId,
					registrationStatusDto.getRegistrationType(), moduleName, registrationStatusDto.getIteration(),
					registrationStatusDto.getWorkflowInstanceId());

			// Calculate age once
			int age = utility.getApplicantAge(registrationId, registrationStatusDto.getRegistrationType(),
					ProviderStageName.DEMO_DEDUPE);
			int ageThreshold = Integer.parseInt(ageLimit);

			boolean shouldPerformDedupe = false;
			// Determine dedupe eligibility based on age and config
			if (age < ageThreshold) {
				shouldPerformDedupe = GLOBAL_CONFIG_TRUE_VALUE.equalsIgnoreCase(infantDedupe);
			} else {
				String demoEnabled = env.getProperty(DEMODEDUPEENABLE, "false");
				shouldPerformDedupe = TRUE.equalsIgnoreCase(demoEnabled.trim());
			}

			// Perform dedupe if eligible
			if (shouldPerformDedupe) {
				isDemoDedupeSkip = false;
				duplicateDtos = performDemoDedupe(registrationStatusDto, object, description);
				isTransactionSuccessful = duplicateDtos.isEmpty();
			}

			// If dedupe skipped
			if (isDemoDedupeSkip) {
				object.setIsValid(Boolean.TRUE);
				registrationStatusDto
						.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
				registrationStatusDto.setStatusComment(StatusUtil.DEMO_DEDUPE_SKIPPED.getMessage());
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
				registrationStatusDto.setSubStatusCode(StatusUtil.DEMO_DEDUPE_SKIPPED.getCode());
				description.setCode(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP_SKIP.getCode());
				description.setMessage(
						PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP_SKIP.getMessage() + " -- " + registrationId);
				registrationStatusDto.setUpdatedBy(DemoDedupeConstants.USER);

				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), description.getCode(), registrationId,
						description.getMessage());
				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationStatusDto.getRegistrationId(),
						DemoDedupeConstants.DEMO_SKIP);
			}
		} else if (packetStatus.equalsIgnoreCase(AbisConstant.POST_ABIS_IDENTIFICATION)) {
			// Process ABIS results
			isTransactionSuccessful = processDemoDedupeRequesthandler(registrationStatusDto, object, description);
		}
		
		demoDedupeStatusDTO.setTransactionSuccessful(isTransactionSuccessful);
		demoDedupeStatusDTO.setDuplicateDtos(duplicateDtos);
		return demoDedupeStatusDTO;
	}

	/**
	 * Perform demo dedupe.
	 *
	 * @param registrationStatusDto the registration status dto
	 * @param object                the object
	 * @param description
	 * @return true, if successful
	 */
	private List<DemographicInfoDto> performDemoDedupe(InternalRegistrationStatusDto registrationStatusDto,
			MessageDTO object, LogDescription description) {
		String registrationId = registrationStatusDto.getRegistrationId();
		
		// Potential Duplicate Ids after performing demo dedupe
		List<DemographicInfoDto> duplicateDtos = demoDedupe.performDedupe(registrationStatusDto.getRegistrationId());
		duplicateDtos.removeIf(dto -> registrationId.equals(dto.getRegId()));

		boolean hasDuplicates = !duplicateDtos.isEmpty();

		if (hasDuplicates) {
			isMatchFound = true;

			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.IN_PROGRESS.toString());
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
			registrationStatusDto.setStatusComment(StatusUtil.POTENTIAL_MATCH_FOUND.getMessage());
			registrationStatusDto.setSubStatusCode(StatusUtil.POTENTIAL_MATCH_FOUND.getCode());

			object.setMessageBusAddress(MessageBusAddress.ABIS_HANDLER_BUS_IN);
			object.setIsValid(Boolean.TRUE);

			description.setCode(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode());
			description.setMessage(DemoDedupeConstants.RECORD_INSERTED_FROM_ABIS_HANDLER + " -- " + registrationId);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(), DemoDedupeConstants.RECORD_INSERTED_FROM_ABIS_HANDLER);
		} else {
			object.setIsValid(Boolean.TRUE);

			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
			registrationStatusDto.setStatusComment(StatusUtil.DEMO_DEDUPE_SUCCESS.getMessage());
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
			registrationStatusDto.setSubStatusCode(StatusUtil.DEMO_DEDUPE_SUCCESS.getCode());
			registrationStatusDto.setUpdatedBy(DemoDedupeConstants.USER);

			description.setCode(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode());
			description.setMessage(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getMessage() + " -- " + registrationId);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), description.getCode(), registrationId,
					description.getMessage());
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(), DemoDedupeConstants.DEMO_SUCCESS);
		}
		return duplicateDtos;
	}

	/**
	 * Gets the latest transaction id.
	 *
	 * @param registrationId the registration id
	 * @return the latest transaction id
	 */
	private String getLatestTransactionId(String registrationId, String process, int iteration,
			String workflowInstanceId) {
		RegistrationStatusEntity entity = registrationStatusDao.find(registrationId, process, iteration,
				workflowInstanceId);
		return entity != null ? entity.getLatestRegistrationTransactionId() : null;
	}

	/**
	 * Process demo dedupe requesthandler.
	 *
	 * @param registrationStatusDto the registration status dto
	 * @param object                the object
	 * @param description
	 * @return true, if successful
	 * @throws ApisResourceAccessException the apis resource access exception
	 * @throws IOException                 Signals that an I/O exception has
	 *                                     occurred.
	 */
	private boolean processDemoDedupeRequesthandler(InternalRegistrationStatusDto registrationStatusDto,
			MessageDTO object, LogDescription description)
			throws ApisResourceAccessException, IOException, JsonProcessingException, PacketManagerException {
		boolean isTransactionSuccessful = false;
		List<String> responsIds = new ArrayList<>();

		// Step 1: Fetch latest transaction ID
		String latestTransactionId = getLatestTransactionId(registrationStatusDto.getRegistrationId(),
				registrationStatusDto.getRegistrationType(), registrationStatusDto.getIteration(),
				registrationStatusDto.getWorkflowInstanceId());

		 // Step 2: Fetch ABIS responses
		List<AbisResponseDto> abisResponseDto = Optional.ofNullable(
				packetInfoManager.getAbisResponseRecords(latestTransactionId, DemoDedupeConstants.IDENTIFY)
		).orElse(Collections.emptyList());

		// Step 3: Process ABIS response status
		for (AbisResponseDto responseDto : abisResponseDto) {
			String statusCode = responseDto.getStatusCode();

			if (statusCode.equalsIgnoreCase(AbisStatusCode.SUCCESS.toString())) {
				responsIds.add(responseDto.getId());
			} else {
				isTransactionSuccessful = true;
				int retryCount = registrationStatusDto.getRetryCount() != null
						? registrationStatusDto.getRetryCount() + 1
						: 1;

				description.setMessage(
						DemoDedupeConstants.SENDING_TO_REPROCESS + " -- " + registrationStatusDto.getRegistrationId());
				registrationStatusDto.setRetryCount(retryCount);
				registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
						.getStatusCode(RegistrationExceptionTypeCode.DEMO_DEDUPE_ABIS_RESPONSE_ERROR));
				registrationStatusDto.setStatusComment(StatusUtil.DEMO_DEDUPE_FAILED_IN_ABIS.getMessage());
				registrationStatusDto.setSubStatusCode(StatusUtil.DEMO_DEDUPE_FAILED_IN_ABIS.getCode());
				registrationStatusDto.setStatusCode(RegistrationStatusCode.REJECTED.toString());

				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationStatusDto.getRegistrationId(),
						DemoDedupeConstants.SENDING_TO_REPROCESS);
			}
		}

		// Step 4: If there are successful IDs, fetch details
		if (!responsIds.isEmpty()) {
			List<AbisResponseDetDto> abisResponseDetDto =
					packetInfoManager.getAbisResponseDetRecordsList(responsIds);
			if (abisResponseDetDto.isEmpty()) {
				object.setIsValid(Boolean.TRUE);
				registrationStatusDto
						.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
				registrationStatusDto.setStatusComment(StatusUtil.DEMO_DEDUPE_SUCCESS.getMessage());
				registrationStatusDto.setSubStatusCode(StatusUtil.DEMO_DEDUPE_SUCCESS.getCode());
				registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());

				description.setCode(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode());
				description.setMessage(PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getMessage());

				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationStatusDto.getRegistrationId(),
						DemoDedupeConstants.NO_DUPLICATES_FOUND);
				isTransactionSuccessful = true;
			} else {
				if (manualVerificationStatus != null
						&& manualVerificationStatus.equalsIgnoreCase(MANUAL_VERIFICATION_STATUS)) {
					// send message to manual adjudication
					object.setInternalError(Boolean.FALSE);
					object.setRid(registrationStatusDto.getRegistrationId());
					object.setIsValid(Boolean.TRUE);
					object.setMessageBusAddress(MessageBusAddress.MANUAL_ADJUDICATION_BUS_IN);
					registrationStatusDto.setStatusCode(RegistrationStatusCode.FAILED.toString());
				} else {
					object.setIsValid(Boolean.FALSE);
					registrationStatusDto.setStatusCode(RegistrationStatusCode.REJECTED.toString());
				}

				registrationStatusDto
						.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.FAILED.toString());
				registrationStatusDto.setStatusComment(StatusUtil.POTENTIAL_MATCH_FOUND_IN_ABIS.getMessage());
				registrationStatusDto.setSubStatusCode(StatusUtil.POTENTIAL_MATCH_FOUND_IN_ABIS.getCode());
				description.setCode(PlatformErrorMessages.RPR_DEMO_SENDING_FOR_MANUAL.getCode());
				description.setMessage(PlatformErrorMessages.RPR_DEMO_SENDING_FOR_MANUAL.getMessage());

				saveManualAdjudicationData(registrationStatusDto, object);

				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationStatusDto.getRegistrationId(),
						DemoDedupeConstants.SENDING_FOR_MANUAL);
			}
		}

		return isTransactionSuccessful;
	}

	/**
	 * Save duplicate dto list.
	 *
	 * @param duplicateDtos         the duplicate dtos
	 * @param registrationStatusDto the registration status dto
	 * @return true, if successful
	 */
	private boolean saveDuplicateDtoList(List<DemographicInfoDto> duplicateDtos,
			InternalRegistrationStatusDto registrationStatusDto, MessageDTO object) {

		if (duplicateDtos == null || duplicateDtos.isEmpty()) {
			object.setIsValid(Boolean.TRUE);
			return false;
		}

		boolean isDataSaved = false;
		int numberOfProcessedPackets = 0;

		String regId = registrationStatusDto.getRegistrationId();
		String regType = registrationStatusDto.getRegistrationType();
		int iteration = registrationStatusDto.getIteration();
		String workflowInstanceId = registrationStatusDto.getWorkflowInstanceId();
		String moduleId = PlatformSuccessMessages.RPR_PKR_DEMO_DE_DUP.getCode();
		String moduleName = ModuleName.DEMO_DEDUPE.name();

		for (DemographicInfoDto demographicInfoDto : duplicateDtos) {
			String matchedRegId = demographicInfoDto.getRegId();

			InternalRegistrationStatusDto potentialMatchRegistrationDto = registrationStatusService
					.getRegistrationStatus(matchedRegId, regType,
							iteration, workflowInstanceId);
			String latestStatus = potentialMatchRegistrationDto.getLatestTransactionStatusCode();
			if (latestStatus == null) {
				continue;
			}

			// Ignore REPROCESS or RE_REGISTER
			if (RegistrationTransactionStatusCode.REPROCESS.toString().equalsIgnoreCase(latestStatus)
					|| AbisConstant.RE_REGISTER.equalsIgnoreCase(latestStatus)) {

				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), regId,
						DemoDedupeConstants.REJECTED_OR_REREGISTER);
				continue;
			}

			// Process only IN_PROGRESS or PROCESSED
			if (RegistrationTransactionStatusCode.IN_PROGRESS.toString().equalsIgnoreCase(latestStatus)
					|| RegistrationTransactionStatusCode.PROCESSED.toString().equalsIgnoreCase(latestStatus)) {


				String latestTransactionId = getLatestTransactionId(registrationStatusDto.getRegistrationId(),
						registrationStatusDto.getRegistrationType(), registrationStatusDto.getIteration(),
						registrationStatusDto.getWorkflowInstanceId());

				RegDemoDedupeListDto regDemoDedupeListDto = new RegDemoDedupeListDto();
				regDemoDedupeListDto.setRegId(registrationStatusDto.getRegistrationId());
				regDemoDedupeListDto.setMatchedRegId(demographicInfoDto.getRegId());
				regDemoDedupeListDto.setRegtrnId(latestTransactionId);
				regDemoDedupeListDto.setIsDeleted(Boolean.FALSE);
				regDemoDedupeListDto.setCrBy(DemoDedupeConstants.CREATED_BY);

				packetInfoManager.saveDemoDedupePotentialData(regDemoDedupeListDto, moduleId, moduleName);
				isDataSaved = true;
				numberOfProcessedPackets++;
			} else {
				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationStatusDto.getRegistrationId(),
						"The packet status is something different");
			}
			if (numberOfProcessedPackets == 0) {
				object.setIsValid(Boolean.TRUE);
			}
		}
		return isDataSaved;
	}

	/**
	 * Save manual adjudication data.
	 *
	 * @param registrationStatusDto the registration status dto
	 * @throws ApisResourceAccessException                the apis resource access
	 *                                                    exception
	 * @throws IOException                                Signals that an I/O
	 *                                                    exception has occurred.
	 * @throws io.mosip.kernel.core.exception.IOException
	 * @throws PacketDecryptionFailureException
	 * @throws RegistrationProcessorCheckedException
	 */
	private void saveManualAdjudicationData(InternalRegistrationStatusDto registrationStatusDto, MessageDTO messageDTO)
			throws ApisResourceAccessException, IOException, JsonProcessingException, PacketManagerException {

		final String regId = registrationStatusDto.getRegistrationId();
		final String regType = registrationStatusDto.getRegistrationType();
		final int iteration = registrationStatusDto.getIteration();
		final String workflowId = registrationStatusDto.getWorkflowInstanceId();

		Set<String> matchedRegIds = abisHandlerUtil.getUniqueRegIds(regId, regType, iteration, workflowId,
				ProviderStageName.DEMO_DEDUPE);

		if (matchedRegIds != null && !matchedRegIds.isEmpty()) {
			String moduleId = PlatformErrorMessages.RPR_DEMO_SENDING_FOR_MANUAL.getCode();
			String moduleName = ModuleName.DEMO_DEDUPE.toString();
			packetInfoManager.saveManualAdjudicationData(matchedRegIds, messageDTO, DedupeSourceName.DEMO, moduleId,
					moduleName, null, null);
		}
		else {
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					regId, DemoDedupeConstants.NO_MATCH_FOUND);
	    }
	}

	private void updateErrorFlags(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object) {
		object.setInternalError(true);
        object.setIsValid(registrationStatusDto.getLatestTransactionStatusCode()
                .equalsIgnoreCase(RegistrationTransactionStatusCode.REPROCESS.toString()));
	}
}
package io.mosip.registration.processor.packet.uploader.service.impl;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.commons.khazana.exception.ObjectStoreAdapterException;
import io.mosip.commons.khazana.spi.ObjectStoreAdapter;
import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.kernel.core.virusscanner.exception.VirusScannerException;
import io.mosip.kernel.core.virusscanner.spi.VirusScanner;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionTypeCode;
import io.mosip.registration.processor.core.constant.LandingZoneTypeConstant;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.ObjectStoreNotAccessibleException;
import io.mosip.registration.processor.core.exception.PacketDecryptionFailureException;
import io.mosip.registration.processor.core.exception.SftpFileOperationException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.LogDescription;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.AdditionalInfoRequestDto;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.status.util.StatusUtil;
import io.mosip.registration.processor.core.status.util.TrimExceptionMessage;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.manager.decryptor.Decryptor;
import io.mosip.registration.processor.packet.manager.utils.ZipUtils;
import io.mosip.registration.processor.packet.storage.dto.ConfigEnum;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.packet.uploader.exception.PacketNotFoundException;
import io.mosip.registration.processor.packet.uploader.service.PacketUploaderService;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.SyncRegistrationDto;
import io.mosip.registration.processor.status.dto.SyncResponseDto;
import io.mosip.registration.processor.status.entity.SyncRegistrationEntity;
import io.mosip.registration.processor.status.exception.TablenotAccessibleException;
import io.mosip.registration.processor.status.service.AdditionalInfoRequestService;
import io.mosip.registration.processor.status.service.RegistrationStatusService;
import io.mosip.registration.processor.status.service.SyncRegistrationService;

/**
 * The Class PacketUploaderServiceImpl.
 *
 * @author Rishabh Keshari
 */
@RefreshScope
@Component
public class PacketUploaderServiceImpl implements PacketUploaderService<MessageDTO> {

    /**
     * The reg proc logger.
     */
    private static Logger regProcLogger = RegProcessorLogger.getLogger(PacketUploaderServiceImpl.class);

    /**
     * The fillesystem manager.
     */
    private static final String USER = "MOSIP_SYSTEM";
    private static final String ZIP = ".zip";
    private static final String JSON = ".json";
    private static final String FORWARD_SLASH = "/";

    @Value("${mosip.regproc.landing.zone.account.name}")
    private String landingZoneAccount;

    @Value("${mosip.regproc.landing.zone.type:ObjectStore}")
    private String landingZoneType;

    @Value("${packet.manager.account.name}")
    private String packetManagerAccount;

    @Value("${packet.manager.iteration.addition.enabled:true}")
    private boolean isIterationAdditionEnabled;

    /**
     * the packet extension(Ex - .zip)
     */
    @Value("${registration.processor.packet.ext}")
    private String extention;

    @Value("${mosip.commons.packetnames}")
    private String packetNames;

    /**
     * The max retry count.
     */
    @Value("${registration.processor.max.retry}")
    private int maxRetryCount;

    @Autowired
    private ObjectStoreAdapter objectStoreAdapter;

    /**
     * The sync registration service.
     */
    @Autowired
    private SyncRegistrationService<SyncResponseDto, SyncRegistrationDto> syncRegistrationService;

    /**
     * The registration status service.
     */
    @Autowired
    private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

    @Autowired
    private AdditionalInfoRequestService additionalInfoRequestService;

    /**
     * The core audit request builder.
     */
    @Autowired
    private AuditLogRequestBuilder auditLogRequestBuilder;

    /**
     * The virus scanner service.
     */
    @Autowired
    private VirusScanner<Boolean, InputStream> virusScannerService;

    @Autowired
    private RegistrationProcessorRestClientService restClient;

    /**
     * The registration status mapper util.
     */
    @Autowired
    private RegistrationExceptionMapperUtil registrationStatusMapperUtil;

    @Autowired
    private Decryptor decryptor;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private Utilities utility;


    /**
     * The is transaction successful.
     */
    boolean isTransactionSuccessful = false;

    /*
     * java class to trim exception message
     */
    private TrimExceptionMessage trimExpMessage = new TrimExceptionMessage();

    /*
     * (non-Javadoc)
     *
     * @see io.mosip.id.issuance.packet.handler.service.PacketUploadService#
     * validatePacket( java.lang.Object)
     */

    @Override
    public MessageDTO validateAndUploadPacket(MessageDTO messageDTO, String stageName) {

        LogDescription description = new LogDescription();
        InternalRegistrationStatusDto dto = new InternalRegistrationStatusDto();
        messageDTO.setInternalError(Boolean.FALSE);
        messageDTO.setIsValid(Boolean.FALSE);
        isTransactionSuccessful = false;
        String registrationId = messageDTO.getRid();
        regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                registrationId, "PacketUploaderServiceImpl::validateAndUploadPacket()::entry");
        SyncRegistrationEntity regEntity = null;

        try {
            long t0 = System.currentTimeMillis();
            regEntity = syncRegistrationService.findByWorkflowInstanceId(messageDTO.getWorkflowInstanceId());
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, "PERF::findByWorkflowInstanceId took " + (System.currentTimeMillis() - t0) + " ms");

            long t1 = System.currentTimeMillis();
            dto = registrationStatusService.getRegistrationStatus(
                    registrationId, messageDTO.getReg_type(), messageDTO.getIteration(), regEntity.getWorkflowInstanceId());
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, "PERF::getRegistrationStatus took " + (System.currentTimeMillis() - t1) + " ms");

            dto.setLatestTransactionTypeCode(RegistrationTransactionTypeCode.UPLOAD_PACKET.toString());
            dto.setRegistrationStageName(stageName);

            long t2 = System.currentTimeMillis();
            final byte[] encryptedByteArray = getPakcetFromDMZ(regEntity.getPacketId(),registrationId);
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, "PERF::getPacketFromDMZ took " + (System.currentTimeMillis() - t2) + " ms"
                            + (encryptedByteArray != null ? ", size=" + encryptedByteArray.length + " bytes" : ", packet=null"));

            if (encryptedByteArray != null) {

                long t3 = System.currentTimeMillis();
                if (validateHashCode(new ByteArrayInputStream(encryptedByteArray), regEntity, registrationId, dto,
                        description)) {
                    regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                            registrationId, "PERF::validateHashCode took " + (System.currentTimeMillis() - t3) + " ms");

                    long t4 = System.currentTimeMillis();
                    InputStream decryptedPacket = decryptor.decrypt(
                            registrationId,
                            utility.getRefId(registrationId, regEntity.getReferenceId()),
                            new ByteArrayInputStream(encryptedByteArray));
                    final byte[] decryptedPacketBytes = IOUtils.toByteArray(decryptedPacket);
                    regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                            registrationId, "PERF::decryptor.decrypt (outer packet) took " + (System.currentTimeMillis() - t4) + " ms");

                    long t5 = System.currentTimeMillis();
                    // Unzip once; ByteArrayInputStream values are resettable for reuse in uploadPacket
                    Map<String, InputStream> unzippedFiles = ZipUtils.unzipAndGetFiles(new ByteArrayInputStream(decryptedPacketBytes));
                    regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                            registrationId, "PERF::unzipAndGetFiles took " + (System.currentTimeMillis() - t5) + " ms"
                                    + ", entries=" + unzippedFiles.size());

                    long t6 = System.currentTimeMillis();
                    if (scanFile(encryptedByteArray, registrationId,
                            regEntity.getReferenceId(), unzippedFiles, dto, description, messageDTO)) {
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                registrationId, "PERF::scanFile (all sub-packets) took " + (System.currentTimeMillis() - t6) + " ms");
                        int retrycount = (dto.getRetryCount() == null) ? 0 : dto.getRetryCount() + 1;
                        dto.setRetryCount(retrycount);
                        if (retrycount < getMaxRetryCount()) {
                            // Reset all ByteArrayInputStreams so uploadPacket can re-read them
                            unzippedFiles.values().forEach(is -> ((ByteArrayInputStream) is).reset());
                            long t7 = System.currentTimeMillis();
                            messageDTO = uploadPacket(regEntity, dto, unzippedFiles, messageDTO, description);
                            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                    registrationId, "PERF::uploadPacket took " + (System.currentTimeMillis() - t7) + " ms");
                            if (messageDTO.getIsValid()) {
                                dto.setLatestTransactionStatusCode(
                                        RegistrationTransactionStatusCode.SUCCESS.toString());
                                isTransactionSuccessful = true;
                                description.setMessage(PlatformSuccessMessages.RPR_PUM_PACKET_UPLOADER.getMessage());
                                regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
                                        LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
                                        description.getMessage());

                            }
                        } else {

                            messageDTO.setInternalError(Boolean.TRUE);
                            description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_RETRY_CNT_FAILURE.getMessage());
                            description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_RETRY_CNT_FAILURE.getCode());
                            dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                                    .getStatusCode(RegistrationExceptionTypeCode.PACKET_UPLOAD_FAILED_ON_MAX_RETRY_CNT));
                            dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
                            dto.setStatusComment(StatusUtil.PACKET_RETRY_CNT_EXCEEDED.getMessage());
                            dto.setSubStatusCode(StatusUtil.PACKET_RETRY_CNT_EXCEEDED.getCode());
                            dto.setUpdatedBy(USER);
                            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
                                    LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
                                    description.getMessage());
                        }
                    }
                }
            } else {
                messageDTO.setInternalError(Boolean.TRUE);

                dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                        .getStatusCode(RegistrationExceptionTypeCode.PACKET_NOT_FOUND_EXCEPTION));
                dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
                dto.setStatusComment(StatusUtil.PACKET_NOT_FOUND_LANDING_ZONE.getMessage());
                dto.setSubStatusCode(StatusUtil.PACKET_NOT_FOUND_LANDING_ZONE.getCode());
                dto.setUpdatedBy(USER);
                description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.getMessage());
                description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.getCode());

            }

        } catch (TablenotAccessibleException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                    .getStatusCode(RegistrationExceptionTypeCode.TABLE_NOT_ACCESSIBLE_EXCEPTION));
            dto.setStatusComment(
                    trimExpMessage.trimExceptionMessage(StatusUtil.DB_NOT_ACCESSIBLE.getMessage() + e.getMessage()));
            dto.setSubStatusCode(StatusUtil.DB_NOT_ACCESSIBLE.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.name()
                            + ExceptionUtils.getStackTrace(e));

            description.setMessage(PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.getMessage());
            description.setCode(PlatformErrorMessages.RPR_RGS_REGISTRATION_TABLE_NOT_ACCESSIBLE.getCode());

        } catch (PacketNotFoundException ex) {
            if (!isPacketAlreadyPresentInObjectStore(messageDTO.getRid(), messageDTO.getReg_type())) {
                messageDTO.setInternalError(Boolean.TRUE);
                dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                        .getStatusCode(RegistrationExceptionTypeCode.PACKET_NOT_FOUND_EXCEPTION));
                dto.setStatusComment(RegistrationExceptionTypeCode.PACKET_NOT_FOUND_EXCEPTION.toString());
                dto.setSubStatusCode(StatusUtil.PACKET_NOT_FOUND_PACKET_STORE.getCode());
                regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                        registrationId,
                        PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.name() + ExceptionUtils.getStackTrace(ex));
                description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.getMessage());
                description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.getCode());
            } else {
                regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
                        LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
                        "Packet is not present in LANDING_ZONE but alrady present in object store. Hence this request will be marked as success.");
                messageDTO.setInternalError(false);
                messageDTO.setIsValid(true);
                isTransactionSuccessful = true;
                dto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
                dto.setStatusComment(StatusUtil.PACKET_ALREADY_UPLOADED.getMessage());
                dto.setSubStatusCode(StatusUtil.PACKET_ALREADY_UPLOADED.getCode());
                dto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
                description.setMessage(PlatformSuccessMessages.RPR_PUM_PACKET_UPLOADER_ALREADY_UPLOADED.getMessage());
                description.setCode(PlatformSuccessMessages.RPR_PUM_PACKET_UPLOADER_ALREADY_UPLOADED.getCode());
            }

        } catch (ApisResourceAccessException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setLatestTransactionStatusCode(
                    registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.NGINX_ACCESS_EXCEPTION));
            dto.setStatusComment(trimExpMessage
                    .trimExceptionMessage(StatusUtil.NGINX_ACCESS_EXCEPTION.getMessage() + e.getMessage()));
            dto.setSubStatusCode(StatusUtil.IO_EXCEPTION.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId,
                    PlatformErrorMessages.RPR_PUM_NGINX_ACCESS_FAILED.name() + ExceptionUtils.getStackTrace(e));

            description.setMessage(PlatformErrorMessages.RPR_PUM_NGINX_ACCESS_FAILED.getMessage());
            description.setCode(PlatformErrorMessages.RPR_PUM_NGINX_ACCESS_FAILED.getCode());
        } catch (IOException | NoSuchAlgorithmException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setLatestTransactionStatusCode(
                    registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.IOEXCEPTION));
            dto.setStatusComment(
                    trimExpMessage.trimExceptionMessage(StatusUtil.IO_EXCEPTION.getMessage() + e.getMessage()));
            dto.setSubStatusCode(StatusUtil.IO_EXCEPTION.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId,
                    PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.name() + ExceptionUtils.getStackTrace(e));
            description.setMessage(PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getMessage());
            description.setCode(PlatformErrorMessages.RPR_SYS_IO_EXCEPTION.getCode());

        } catch (PacketDecryptionFailureException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
            dto.setStatusComment(StatusUtil.PACKET_DECRYPTION_FAILED.getMessage());
            dto.setSubStatusCode(StatusUtil.PACKET_DECRYPTION_FAILED.getCode());
            dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                    .getStatusCode(RegistrationExceptionTypeCode.PACKET_DECRYPTION_FAILURE_EXCEPTION));
            description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_DECRYPTION_FAILED.getMessage());
            description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_DECRYPTION_FAILED.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, ExceptionUtils.getStackTrace(e));

        } catch (ObjectStoreNotAccessibleException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
            dto.setStatusComment(StatusUtil.OBJECT_STORE_EXCEPTION.getMessage());
            dto.setSubStatusCode(StatusUtil.OBJECT_STORE_EXCEPTION.getCode());
            dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                    .getStatusCode(RegistrationExceptionTypeCode.OBJECT_STORE_EXCEPTION));
            description.setMessage(PlatformErrorMessages.OBJECT_STORE_NOT_ACCESSIBLE.getMessage());
            description.setCode(PlatformErrorMessages.OBJECT_STORE_NOT_ACCESSIBLE.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, PlatformErrorMessages.OBJECT_STORE_NOT_ACCESSIBLE.name()
                            + ExceptionUtils.getStackTrace(e));
        } catch (Exception e) {
            messageDTO.setInternalError(Boolean.TRUE);
            dto.setLatestTransactionStatusCode(
                    registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.EXCEPTION));
            dto.setStatusComment(trimExpMessage
                    .trimExceptionMessage(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getMessage() + e.getMessage()));
            dto.setSubStatusCode(StatusUtil.UNKNOWN_EXCEPTION_OCCURED.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId,
                    PlatformErrorMessages.RPR_PKR_UNKNOWN_EXCEPTION.name() + ExceptionUtils.getStackTrace(e));
            description.setMessage(PlatformErrorMessages.RPR_PKR_UNKNOWN_EXCEPTION.getMessage());
            description.setCode(PlatformErrorMessages.RPR_PKR_UNKNOWN_EXCEPTION.getCode());

        } finally {
            if (messageDTO.getInternalError()) {
                updateErrorFlags(dto, messageDTO);
            }
            /** Module-Id can be Both Success/Error code */
            String moduleId = isTransactionSuccessful ? PlatformSuccessMessages.RPR_PUM_PACKET_UPLOADER.getCode()
                    : description.getCode();
            String moduleName = ModuleName.PACKET_UPLOAD.toString();
            registrationStatusService.updateRegistrationStatus(dto, moduleId, moduleName);
            String eventId = "";
            String eventName = "";
            String eventType = "";
            eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
            eventName = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventName.UPDATE.toString()
                    : EventName.EXCEPTION.toString();
            eventType = eventId.equalsIgnoreCase(EventId.RPR_402.toString()) ? EventType.BUSINESS.toString()
                    : EventType.SYSTEM.toString();

            auditLogRequestBuilder.createAuditRequestBuilder(description.getMessage(), eventId, eventName, eventType,
                    moduleId, moduleName, registrationId);

        }

        regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                registrationId, "PacketUploaderServiceImpl::validateAndUploadPacket()::exit");
        return messageDTO;
    }

    /**
     * Scan file.
     *
     * @param input    the input stream
     * @param refId
     * @param description
     * @return true, if successful
     * @throws IOException
     * @throws ApisResourceAccessException
     */
    private boolean scanFile(final byte[] input, String id, String refId, final Map<String, InputStream> sourcePackets, InternalRegistrationStatusDto dto,
                             LogDescription description, MessageDTO messageDTO) throws ApisResourceAccessException, PacketDecryptionFailureException {
        boolean isInputFileClean = false;
        try {
            InputStream packet = new ByteArrayInputStream(input);
            // scanning the top level packet
            long vs0 = System.currentTimeMillis();
            isInputFileClean = virusScannerService.scanFile(packet);
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    id, "PERF::virusScan [encrypted-outer-packet] took " + (System.currentTimeMillis() - vs0) + " ms");

            if (isInputFileClean) {
                // scanning the source packets (Like - id, evidence, optional packets).
                for (final Map.Entry<String, InputStream> source : sourcePackets.entrySet()) {
                    if (source.getKey().endsWith(ZIP)) {
                        long vsd0 = System.currentTimeMillis();
                        InputStream decryptedData = decryptor
                                .decrypt(id, utility.getRefId(id, refId), source.getValue());
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                id, "PERF::decryptor.decrypt [" + source.getKey() + "] took " + (System.currentTimeMillis() - vsd0) + " ms");
                        long vs1 = System.currentTimeMillis();
                        isInputFileClean = virusScannerService.scanFile(decryptedData);
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                id, "PERF::virusScan [" + source.getKey() + "] took " + (System.currentTimeMillis() - vs1) + " ms");
                    } else {
                        long vs2 = System.currentTimeMillis();
                        isInputFileClean = virusScannerService.scanFile(source.getValue());
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                id, "PERF::virusScan [" + source.getKey() + "] took " + (System.currentTimeMillis() - vs2) + " ms");
                    }
                    if (!isInputFileClean)
                        break;
                }
            }
            if (!isInputFileClean) {
                description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCAN_FAILED.getMessage());
                description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCAN_FAILED.getCode());
                dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
                dto.setStatusComment(StatusUtil.VIRUS_SCANNER_FAILED_UPLOADER.getMessage());
                dto.setSubStatusCode(StatusUtil.VIRUS_SCANNER_FAILED_UPLOADER.getCode());
                dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                        .getStatusCode(RegistrationExceptionTypeCode.VIRUS_SCAN_FAILED_EXCEPTION));
                regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
                        LoggerFileConstant.REGISTRATIONID.toString(), id,
                        PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCAN_FAILED.getMessage());
            }
        } catch (VirusScannerException e) {
            messageDTO.setInternalError(Boolean.TRUE);
            description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCANNER_SERVICE_FAILED.getMessage());
            description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCANNER_SERVICE_FAILED.getCode());
            dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
            dto.setStatusComment(trimExpMessage.trimExceptionMessage(
                    StatusUtil.VIRUS_SCANNER_SERVICE_NOT_ACCESSIBLE.getMessage() + " " + e.getMessage()));
            dto.setSubStatusCode(StatusUtil.VIRUS_SCANNER_SERVICE_NOT_ACCESSIBLE.getCode());
            dto.setLatestTransactionStatusCode(registrationStatusMapperUtil
                    .getStatusCode(RegistrationExceptionTypeCode.VIRUS_SCANNER_SERVICE_FAILED));
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    id, PlatformErrorMessages.RPR_PUM_PACKET_VIRUS_SCANNER_SERVICE_FAILED.getMessage()
                            + ExceptionUtils.getStackTrace(e));

        }
        return isInputFileClean;
    }

    /**
     * Validate hash code.
     *
     * @param registrationId the registration id
     * @param inputStream    the input stream
     * @param registrationId
     * @param description
     * @throws IOException Signals that an I/O exception has occurred.
     */
    private boolean validateHashCode(InputStream inputStream, SyncRegistrationEntity regEntity, String registrationId,
                                     InternalRegistrationStatusDto dto, LogDescription description) throws IOException, NoSuchAlgorithmException {
        boolean isValidHash = false;
        byte[] isbytearray = IOUtils.toByteArray(inputStream);
        String hashSequence = HMACUtils2.digestAsPlainText(isbytearray);
        String packetHashSequence = regEntity.getPacketHashValue();
        if (!(MessageDigest.isEqual(packetHashSequence.getBytes(), hashSequence.getBytes()))) {
            description.setMessage(PlatformErrorMessages.RPR_PKR_PACKET_HASH_NOT_EQUALS_SYNCED_HASH.getMessage());
            description.setCode(PlatformErrorMessages.RPR_PKR_PACKET_HASH_NOT_EQUALS_SYNCED_HASH.getCode());
            dto.setLatestTransactionStatusCode(
                    registrationStatusMapperUtil.getStatusCode(RegistrationExceptionTypeCode.PACKET_HASH_VALIDATION_FAILED));
            dto.setStatusCode(RegistrationStatusCode.FAILED.toString());
            dto.setStatusComment(StatusUtil.PACKET_HASHCODE_VALIDATION_FAILED.getMessage());
            dto.setSubStatusCode(StatusUtil.PACKET_HASHCODE_VALIDATION_FAILED.getCode());
            regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, PlatformErrorMessages.RPR_PKR_PACKET_HASH_NOT_EQUALS_SYNCED_HASH.getMessage());

            return isValidHash;
        } else {
            isValidHash = true;
            return isValidHash;
        }
    }

    /**
     * Uploadpacket.
     *
     * @param dto            the dto
     * @param sourcePackets  source packets
     * @param object         the object
     * @param description
     * @return the message DTO
     * @throws IOException                Signals that an I/O exception has occurred.
     * @throws SftpFileOperationException
     */
    private MessageDTO uploadPacket(SyncRegistrationEntity regEntity, InternalRegistrationStatusDto dto, final Map<String, InputStream> sourcePackets,
                                    MessageDTO object, LogDescription description) throws ObjectStoreNotAccessibleException {

        object.setIsValid(false);
        String registrationId = dto.getRegistrationId();
        // Fetch additionalInfoRequest once (instead of once per sub-packet in getFinalKey)
        long tai0 = System.currentTimeMillis();
        AdditionalInfoRequestDto additionalInfoRequestDto = isIterationAdditionEnabled ?
                additionalInfoRequestService.getAdditionalInfoRequestByRegIdAndProcessAndIteration(
                        object.getRid(), object.getReg_type(), object.getIteration()) : null;
        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                registrationId, "PERF::getAdditionalInfoRequest took " + (System.currentTimeMillis() - tai0) + " ms");

        // Upload sub-packets and metadata in parallel using virtual threads
        ExecutorService uploadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            // Phase 1: upload all ZIP sub-packets in parallel
            long phase1Start = System.currentTimeMillis();
            List<CompletableFuture<Void>> zipFutures = new ArrayList<>();
            for (Map.Entry<String, InputStream> entry : sourcePackets.entrySet()) {
                if (entry.getKey().endsWith(ZIP)) {
                    final String objStoreKey = isIterationAdditionEnabled ?
                            getFinalKey(regEntity, entry.getKey().replace(ZIP, ""), object, additionalInfoRequestDto)
                            : entry.getKey().replace(ZIP, "");
                    final InputStream entryStream = entry.getValue();
                    final String entryKey = entry.getKey();
                    zipFutures.add(CompletableFuture.runAsync(() -> {
                        long put0 = System.currentTimeMillis();
                        boolean result = objectStoreAdapter.putObject(packetManagerAccount, registrationId,
                                null, null, objStoreKey, entryStream);
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                registrationId, "PERF::objectStoreAdapter.putObject [" + entryKey + "] took " + (System.currentTimeMillis() - put0) + " ms");
                        if (!result)
                            throw new CompletionException(new ObjectStoreNotAccessibleException("Failed to store packet : " + entryKey));
                    }, uploadExecutor));
                }
            }
            try {
                CompletableFuture.allOf(zipFutures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof ObjectStoreNotAccessibleException) throw (ObjectStoreNotAccessibleException) cause;
                throw new ObjectStoreNotAccessibleException(cause.getMessage(), cause);
            }
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, "PERF::Phase1 (ZIP uploads, " + zipFutures.size() + " parallel) total wall-time " + (System.currentTimeMillis() - phase1Start) + " ms");

            // Phase 2: upload all JSON metadata in parallel (after ZIPs are stored)
            long phase2Start = System.currentTimeMillis();
            List<CompletableFuture<Void>> jsonFutures = new ArrayList<>();
            for (Map.Entry<String, InputStream> entry : sourcePackets.entrySet()) {
                if (entry.getKey().endsWith(JSON)) {
                    byte[] bytearray = IOUtils.toByteArray(entry.getValue());
                    LinkedHashMap<String, Object> currentIdMap = (LinkedHashMap<String, Object>) mapper.readValue(new String(bytearray), LinkedHashMap.class);
                    final String objStoreKey = isIterationAdditionEnabled ?
                            getFinalKey(regEntity, entry.getKey().replace(JSON, ""), object, additionalInfoRequestDto)
                            : entry.getKey().replace(JSON, "");
                    final String entryKey = entry.getKey();
                    jsonFutures.add(CompletableFuture.runAsync(() -> {
                        long meta0 = System.currentTimeMillis();
                        objectStoreAdapter.addObjectMetaData(packetManagerAccount, registrationId,
                                null, null, objStoreKey, currentIdMap);
                        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                                registrationId, "PERF::objectStoreAdapter.addObjectMetaData [" + entryKey + "] took " + (System.currentTimeMillis() - meta0) + " ms");
                    }, uploadExecutor));
                }
            }
            try {
                CompletableFuture.allOf(jsonFutures.toArray(new CompletableFuture[0])).join();
            } catch (CompletionException e) {
                Throwable cause = e.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                throw new ObjectStoreNotAccessibleException(cause.getMessage(), cause);
            }
            regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                    registrationId, "PERF::Phase2 (JSON metadata, " + jsonFutures.size() + " parallel) total wall-time " + (System.currentTimeMillis() - phase2Start) + " ms");
        } catch (ObjectStoreNotAccessibleException e) {
            object.setIsValid(false);
            object.setInternalError(true);
            throw e;
        } catch (Exception e) {
            object.setIsValid(false);
            object.setInternalError(true);
            throw new ObjectStoreNotAccessibleException(e.getMessage(), e);
        } finally {
            uploadExecutor.close();
        }


        dto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());
        dto.setStatusComment(StatusUtil.PACKET_UPLOADED.getMessage());
        dto.setSubStatusCode(StatusUtil.PACKET_UPLOADED.getCode());
        dto.setUpdatedBy(USER);
        object.setInternalError(false);
        object.setIsValid(true);
        object.setRid(registrationId);

        isTransactionSuccessful = true;
        description.setMessage(PlatformErrorMessages.RPR_PUM_PACKET_DELETION_INFO.getMessage());
        description.setCode(PlatformErrorMessages.RPR_PUM_PACKET_DELETION_INFO.getCode());

        regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
                LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
                PlatformErrorMessages.RPR_PUM_PACKET_DELETION_INFO.getMessage());

        return object;
    }

    /**
     * Get max retry count.
     *
     * @return maxRetryCount
     */
    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    private byte[] getPakcetFromDMZ(String packetId, String registrationId) throws ApisResourceAccessException, ObjectStoreNotAccessibleException, IOException {
        List<String> pathSegment = new ArrayList<>();
        pathSegment.add(packetId + extention);
        byte[] packet = null;

        try {
            if(landingZoneType.equalsIgnoreCase(LandingZoneTypeConstant.DMZ_SERVER)) {
                packet = (byte[]) restClient.getApi(ApiName.NGINXDMZURL, pathSegment, "", null, byte[].class);
            }
            else if(landingZoneType.equalsIgnoreCase(LandingZoneTypeConstant.OBJECT_STORE)) {
                packet=IOUtils.toByteArray(objectStoreAdapter.getObject(landingZoneAccount, registrationId, null, null, packetId));
                if(packet==null) {
                    throw new ObjectStoreNotAccessibleException("Failed to get packet : " +packetId);
                }
            }
        } catch (ApisResourceAccessException e) {
            if (e.getCause() instanceof HttpClientErrorException) {
                HttpClientErrorException ex = (HttpClientErrorException) e.getCause();
                if (ex.getStatusCode().equals(HttpStatus.NOT_FOUND))
                    throw new PacketNotFoundException(PlatformErrorMessages.RPR_PUM_PACKET_NOT_FOUND_EXCEPTION.getMessage(), ex);
            } else
                throw e;
        } catch(ObjectStoreAdapterException e) {
            throw e;
        }
        return packet;
    }

    /**
     * Modify process name to add iteration before uploading to object store.
     * @param regEntity
     * @param packetKey
     * @return
     */
    private String getFinalKey(SyncRegistrationEntity regEntity, String packetKey, MessageDTO messageDTO,
                               AdditionalInfoRequestDto additionalInfoRequestDto) {
        String[] tempKeys = packetKey.split(FORWARD_SLASH);
        // if known format of source/process/objectName only then modify the process
        if (tempKeys != null && tempKeys.length == 3) {
            String source = tempKeys[0];
            String process = tempKeys[1];
            String objectName = tempKeys[2];
            // additionalInfoRequestDto is pre-fetched once by caller to avoid repeated DB calls
            if (additionalInfoRequestDto != null &&
                    additionalInfoRequestDto.getAdditionalInfoReqId().equals(regEntity.getAdditionalInfoReqId())) {
                return source + FORWARD_SLASH + process + "-" + messageDTO.getIteration() + FORWARD_SLASH + objectName;
            } else
                return packetKey;

        } else {
            regProcLogger.warn("PacketUploaderServiceImpl::getFinalKey() The packet key is not in source/process/objectName format "
                    + packetKey + " id : " + messageDTO.getRid());
            return packetKey;
        }
    }

    private void updateErrorFlags(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object) {
        object.setInternalError(true);
        if (registrationStatusDto.getLatestTransactionStatusCode()
                .equalsIgnoreCase(RegistrationTransactionStatusCode.REPROCESS.toString())) {
            object.setIsValid(true);
        } else {
            object.setIsValid(false);
        }
    }


    public boolean isPacketAlreadyPresentInObjectStore(String id, String process) {

        for (String name : packetNames.split(",")) {
            boolean isPresent = objectStoreAdapter.exists(packetManagerAccount, id, utility.getDefaultSource(process, ConfigEnum.READER), process, id+ "_" + name);
            if (!isPresent) {
                regProcLogger.error(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
                        id, name + " : packet not present in object store.");
                return false;
            }
        }
        return true;
    }



}
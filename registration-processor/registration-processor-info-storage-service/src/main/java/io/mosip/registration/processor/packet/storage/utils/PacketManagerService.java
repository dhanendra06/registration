package io.mosip.registration.processor.packet.storage.utils;

import java.util.*;

import io.mosip.registration.processor.core.packet.dto.packetmanager.TagRequestDto;
import io.mosip.registration.processor.core.packet.dto.packetmanager.TagResponseDto;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils2;

import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.common.rest.dto.ErrorDTO;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;

import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.exception.PacketManagerNonRecoverableException;
import io.mosip.registration.processor.packet.storage.exception.ObjectDoesnotExistsException;

import io.mosip.registration.processor.core.http.RequestWrapper;
import io.mosip.registration.processor.core.http.ResponseWrapper;

import io.mosip.registration.processor.core.logger.RegProcessorLogger;

import io.mosip.registration.processor.packet.storage.dto.*;


@Component
public class PacketManagerService {

    private static final Logger log = RegProcessorLogger.getLogger(PacketManagerService.class);

    private static final String ID = "mosip.commons.packetmanager";
    private static final String VERSION = "v1";
    private static final String ERR_NOT_EXISTS = "KER-PUT-027";
    private static final List<String> NON_RECOVERABLE = List.of("KER-PUT-019");

    @Autowired
    @Qualifier("selfTokenWebClient")
    private WebClient webClient;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private Environment env;

    @PostConstruct
    private void config() {
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /* ============================================================
       SAFE ERROR HANDLING
       ============================================================ */

    private void throwMappedError(String method, String id, ErrorDTO err) throws PacketManagerException {
        log.error(LoggerFileConstant.SESSIONID.toString(),
                LoggerFileConstant.REGISTRATIONID.toString(),
                id,
                "PacketManagerService." + method +
                        " FAILED :: code=" + err.getErrorCode() + ", msg=" + err.getMessage()
        );

        String code = err.getErrorCode();

        if (ERR_NOT_EXISTS.equals(code))
            throw new ObjectDoesnotExistsException(code, err.getMessage());

        if (NON_RECOVERABLE.contains(code))
            throw new PacketManagerNonRecoverableException(code, err.getMessage());

        throw new PacketManagerException(code, err.getMessage());
    }

    private void validateErrors(String method, String id, List<ErrorDTO> errors) throws PacketManagerException {
        if (errors != null && !errors.isEmpty()) {
            throwMappedError(method, id, errors.get(0));
        }
    }

    private <T> RequestWrapper<T> wrapRequest(T body) {
        RequestWrapper<T> r = new RequestWrapper<>();
        r.setId(ID);
        r.setVersion(VERSION);
        r.setRequesttime(DateUtils2.getUTCCurrentDateTime());
        r.setRequest(body);
        return r;
    }

    /* ============================================================
       UNIVERSAL POST HANDLER (FINAL VERSION)
       ============================================================ */
    private <T> T call(ApiName apiName,
                       Object request,
                       ParameterizedTypeReference<ResponseWrapper<T>> type,
                       String method,
                       String rid) throws PacketManagerException, ApisResourceAccessException {

        String url = env.getProperty(apiName.name());
        if (url == null)
            throw new ApisResourceAccessException("Missing URL for " + apiName);

        ResponseWrapper<T> resp;
        try {
            resp = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(type)
                    .block();
        } catch (Exception e) {
            throw new PacketManagerException("NETWORK_ERROR", e.getMessage());
        }

        if (resp == null)
            throw new PacketManagerException("EMPTY_RESPONSE", "PacketManager returned null");

        validateErrors(method, rid, resp.getErrors());

        return resp.getResponse();
    }

    /* ============================================================
       FIELDS
       ============================================================ */

    public String getField(String id, String field, String source, String process) throws PacketManagerException, ApisResourceAccessException {

        FieldDto dto = new FieldDto(id, field, source, process, false);

        FieldResponseDto result = call(
                ApiName.PACKETMANAGER_SEARCH_FIELD,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<FieldResponseDto>>() {},
                "getField", id
        );

        if (result == null || result.getFields() == null) return null;

        String val = result.getFields().get(field);
        return (val != null && val.equals("null")) ? null : val;
    }

    public Map<String, String> getFields(String id, List<String> fields, String source, String process) throws PacketManagerException, ApisResourceAccessException {

        FieldDtos dto = new FieldDtos(id, fields, source, process, false);

        FieldResponseDto result = call(
                ApiName.PACKETMANAGER_SEARCH_FIELDS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<FieldResponseDto>>() {},
                "getFields", id
        );

        return (result == null || result.getFields() == null)
                ? Collections.emptyMap()
                : result.getFields();
    }

    /* ============================================================
       DOCUMENT
       ============================================================ */

    public Document getDocument(String id, String doc, String src, String process) throws PacketManagerException, ApisResourceAccessException {

        DocumentDto dto = new DocumentDto(id, doc, src, process);

        Document result = call(
                ApiName.PACKETMANAGER_SEARCH_DOCUMENT,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<Document>>() {},
                "getDocument", id
        );

        return result;
    }

    public Document getDocument(String id, String documentName, String process) throws PacketManagerException, ApisResourceAccessException {
        return getDocument(id, documentName, null, process);
    }

    /* ============================================================
       VALIDATE PACKET
       ============================================================ */

    public ValidatePacketResponse validate(String id, String source, String process) throws PacketManagerException, ApisResourceAccessException {

        InfoDto dto = new InfoDto(id, source, process, false);

        return call(
                ApiName.PACKETMANAGER_VALIDATE,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<ValidatePacketResponse>>() {},
                "validate", id
        );
    }

    /* ============================================================
       AUDITS (FULL CONTROLLED TYPE)
       ============================================================ */

    public List<FieldResponseDto> getAudits(String id, String src, String process) throws PacketManagerException, ApisResourceAccessException {

        InfoDto dto = new InfoDto(id, src, process, false);

        List<FieldResponseDto> list = call(
                ApiName.PACKETMANAGER_SEARCH_AUDITS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<List<FieldResponseDto>>>() {},
                "getAudits", id
        );

        return (list == null) ? Collections.emptyList() : list;
    }

    /* ============================================================
       BIOMETRICS
       ============================================================ */

    public BiometricRecord getBiometrics(String id, String person, List<String> mods,
                                         String src, String process) throws PacketManagerException, ApisResourceAccessException {

        BiometricRequestDto dto = new BiometricRequestDto(id, person, mods, src, process, false);

        return call(
                ApiName.PACKETMANAGER_SEARCH_BIOMETRICS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<BiometricRecord>>() {},
                "getBiometrics", id
        );
    }

    /* ============================================================
       META INFO
       ============================================================ */

    public Map<String, String> getMetaInfo(String id, String src, String process) throws PacketManagerException, ApisResourceAccessException {

        InfoDto dto = new InfoDto(id, src, process, false);

        FieldResponseDto map = call(
                ApiName.PACKETMANAGER_SEARCH_METAINFO,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<FieldResponseDto>>() {},
                "getMetaInfo", id
        );

        return (map == null || map.getFields() == null)
                ? Collections.emptyMap()
                : map.getFields();
    }

    /* ============================================================
       INFO
       ============================================================ */

    public InfoResponseDto info(String id) throws PacketManagerException, ApisResourceAccessException {

        InfoRequestDto dto = new InfoRequestDto(id);

        return call(
                ApiName.PACKETMANAGER_INFO,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<InfoResponseDto>>() {},
                "info", id
        );
    }

    /* ============================================================
       TAGS
       ============================================================ */

    public void addOrUpdateTags(String id, Map<String, String> tags) throws PacketManagerException, ApisResourceAccessException {

        UpdateTagRequestDto dto = new UpdateTagRequestDto(id, tags);

        call(
                ApiName.PACKETMANAGER_UPDATE_TAGS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<Void>>() {},
                "addOrUpdateTags", id
        );
    }

    public void deleteTags(String id, List<String> tags) throws PacketManagerException, ApisResourceAccessException {

        DeleteTagRequestDTO dto = new DeleteTagRequestDTO(id, tags);

        call(
                ApiName.PACKETMANAGER_DELETE_TAGS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<DeleteTagResponseDTO>>() {},
                "deleteTags", id
        );
    }

    public Map<String, String> getTags(String id, List<String> tagNames) throws PacketManagerException, ApisResourceAccessException {

        TagRequestDto dto = new TagRequestDto(id, tagNames);

        TagResponseDto resp = call(
                ApiName.PACKETMANAGER_GET_TAGS,
                wrapRequest(dto),
                new ParameterizedTypeReference<ResponseWrapper<TagResponseDto>>() {},
                "getTags", id
        );

        return (resp != null) ? resp.getTags() : null;
    }

    public Map<String, String> getAllTags(String id) throws PacketManagerException, ApisResourceAccessException {
        return getTags(id, null);
    }
}

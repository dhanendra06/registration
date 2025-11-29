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

    private static final String ID = "mosip.commmons.packetmanager";
    private static final String VERSION = "v1";
    private static final String ERR_NOT_EXISTS = "KER-PUT-027";
    private static final List<String> NON_RECOVERABLE = Arrays.asList("KER-PUT-019");

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
       FAST-PATH HELPERS
       ============================================================ */

    private void failFast(String method, String id, ErrorDTO err) throws PacketManagerException {
        log.error(LoggerFileConstant.SESSIONID.toString(),
                LoggerFileConstant.REGISTRATIONID.toString(),
                id,
                "PacketManagerService." + method + " FAILED :: code=" + err.getErrorCode() + ", msg=" + err.getMessage()
        );

        String code = err.getErrorCode();
        String msg = err.getMessage();

        if (ERR_NOT_EXISTS.equals(code))
            throw new ObjectDoesnotExistsException(code, msg);

        if (NON_RECOVERABLE.contains(code))
            throw new PacketManagerNonRecoverableException(code, msg);

        throw new PacketManagerException(code, msg);
    }

    private void checkErrors(String method, String id, List<ErrorDTO> errors)  {
        if (errors != null && !errors.isEmpty()) {
            try {
                failFast(method, id, errors.get(0));
            } catch (PacketManagerException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private <T> T convert(Object src, Class<T> type) {
        return src == null ? null : mapper.convertValue(src, type);
    }

    private <T> RequestWrapper<T> req(T body) {
        RequestWrapper<T> r = new RequestWrapper<>();
        r.setId(ID);
        r.setVersion(VERSION);
        r.setRequesttime(DateUtils2.getUTCCurrentDateTime());
        r.setRequest(body);
        return r;
    }

    /* ============================================================
       UNIFIED POST HANDLER  (WebClient)
       ============================================================ */
    private <T> ResponseWrapper<T> post(ApiName apiName, Object body, Class<T> responseType)
             {

        try {
            String apiHostIpPort = env.getProperty(apiName.name());
            if (apiHostIpPort == null)
                throw new ApisResourceAccessException("Missing URL for " + apiName.name());

            String url = apiHostIpPort;

            ParameterizedTypeReference<ResponseWrapper<T>> ptr =
                    new ParameterizedTypeReference<ResponseWrapper<T>>() {};

            return webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(ptr)
                    .block();

        } catch (Exception e) {
            log.error(LoggerFileConstant.SESSIONID.toString(),
                    LoggerFileConstant.APPLICATIONID.toString(),
                    apiName.name(),
                    "POST failed :: " + e.getMessage()
            );
        }
                 return null;
             }


    /* ============================================================
       FIELDS
       ============================================================ */

    public String getField(String id, String field, String source, String process)
            throws Exception {

        FieldDto dto = new FieldDto(id, field, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                post(ApiName.PACKETMANAGER_SEARCH_FIELD, req(dto), FieldResponseDto.class);

        checkErrors("getField", id, resp.getErrors());

        FieldResponseDto f = resp.getResponse();
        if (f == null || f.getFields() == null) return null;

        String v = f.getFields().get(field);
        return (v != null && v.equalsIgnoreCase("null")) ? null : v;
    }


    public Map<String, String> getFields(String id, List<String> fields, String source, String process)
    {

        FieldDtos dto = new FieldDtos(id, fields, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                post(ApiName.PACKETMANAGER_SEARCH_FIELDS, req(dto), FieldResponseDto.class);

        checkErrors("getFields", id, resp.getErrors());

        return resp.getResponse().getFields();
    }

    /* ============================================================
       DOCUMENT
       ============================================================ */

    public Document getDocument(String id, String doc, String src, String process)
             {

        DocumentDto dto = new DocumentDto(id, doc, src, process);

        ResponseWrapper<Document> resp =
                post(ApiName.PACKETMANAGER_SEARCH_DOCUMENT, req(dto), Document.class);

        checkErrors("getDocument", id, resp.getErrors());

        return resp.getResponse();
    }

    public Document getDocument(String id, String documentName, String process)
    {
        return getDocument(id, documentName, null, process);
    }


    /* ============================================================
       VALIDATE
       ============================================================ */

    public ValidatePacketResponse validate(String id, String source, String process)
    {

        InfoDto dto = new InfoDto(id, source, process, false);

        ResponseWrapper<ValidatePacketResponse> resp =
                null;
        resp = post(ApiName.PACKETMANAGER_VALIDATE, req(dto), ValidatePacketResponse.class);

        checkErrors("validate", id, resp.getErrors());

        return resp.getResponse();
    }

    /* ============================================================
       AUDITS
       ============================================================ */

    public List<FieldResponseDto> getAudits(String id, String src, String process) {

        InfoDto dto = new InfoDto(id, src, process, false);

        ResponseWrapper<List> resp =
                post(ApiName.PACKETMANAGER_SEARCH_AUDITS, req(dto), List.class);

        checkErrors("getAudits", id, resp.getErrors());

        List raw = resp.getResponse();
        if (raw == null) return new ArrayList<>();

        List<FieldResponseDto> out = new ArrayList<>();
        raw.forEach(o -> out.add(convert(o, FieldResponseDto.class)));

        return out;
    }

    /* ============================================================
       BIOMETRICS
       ============================================================ */

    public BiometricRecord getBiometrics(String id, String person, List<String> mods,
                                         String source, String process)
             {

        BiometricRequestDto dto = new BiometricRequestDto(id, person, mods, source, process, false);

        ResponseWrapper<BiometricRecord> resp =
                post(ApiName.PACKETMANAGER_SEARCH_BIOMETRICS, req(dto), BiometricRecord.class);

        checkErrors("getBiometrics", id, resp.getErrors());

        return resp.getResponse();
    }

    /* ============================================================
       META INFO
       ============================================================ */

    public Map<String, String> getMetaInfo(String id, String source, String process)
             {

        InfoDto dto = new InfoDto(id, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                post(ApiName.PACKETMANAGER_SEARCH_METAINFO, req(dto), FieldResponseDto.class);

        checkErrors("getMetaInfo", id, resp.getErrors());

        return resp.getResponse().getFields();
    }

    /* ============================================================
       INFO
       ============================================================ */

    public InfoResponseDto info(String id) {

        InfoRequestDto dto = new InfoRequestDto(id);

        ResponseWrapper<InfoResponseDto> resp =
                post(ApiName.PACKETMANAGER_INFO, req(dto), InfoResponseDto.class);

        checkErrors("info", id, resp.getErrors());

        return resp.getResponse();
    }

    /* ============================================================
       TAGS
       ============================================================ */

    public void addOrUpdateTags(String id, Map<String, String> tags)
             {

        UpdateTagRequestDto dto = new UpdateTagRequestDto(id, tags);

        ResponseWrapper<Void> resp =
                post(ApiName.PACKETMANAGER_UPDATE_TAGS, req(dto), Void.class);

        checkErrors("addOrUpdateTags", id, resp.getErrors());
    }


    public void deleteTags(String id, List<String> tags)
            throws Exception {

        DeleteTagRequestDTO dto = new DeleteTagRequestDTO(id, tags);

        ResponseWrapper<DeleteTagResponseDTO> resp =
                post(ApiName.PACKETMANAGER_DELETE_TAGS, req(dto), DeleteTagResponseDTO.class);

        checkErrors("deleteTags", id, resp.getErrors());
    }


    public Map<String, String> getTags(String id, List<String> tagNames) throws PacketManagerException {

        TagRequestDto dto = new TagRequestDto(id, tagNames);

        ResponseWrapper<TagResponseDto> resp =
                post(ApiName.PACKETMANAGER_GET_TAGS, req(dto), TagResponseDto.class);

        List<ErrorDTO> errors = resp.getErrors();
        if (errors != null && !errors.isEmpty()) {
            ErrorDTO e = errors.get(0);
            if ("KER-PUT-024".equalsIgnoreCase(e.getErrorCode())) return null;
            failFast("getTags", id, e);
        }

        TagResponseDto r = resp.getResponse();
        return (r != null) ? r.getTags() : null;
    }

    public Map<String, String> getAllTags(String id) throws PacketManagerException {
        return getTags(id, null);
    }
}

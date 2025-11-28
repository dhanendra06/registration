package io.mosip.registration.processor.packet.storage.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.mosip.registration.processor.core.packet.dto.packetmanager.TagRequestDto;
import io.mosip.registration.processor.core.packet.dto.packetmanager.TagResponseDto;
import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.mosip.registration.processor.packet.storage.dto.BiometricRequestDto;
import io.mosip.registration.processor.packet.storage.dto.DeleteTagRequestDTO;
import io.mosip.registration.processor.packet.storage.dto.DeleteTagResponseDTO;
import io.mosip.registration.processor.packet.storage.dto.DocumentDto;
import io.mosip.registration.processor.packet.storage.dto.FieldDto;
import io.mosip.registration.processor.packet.storage.dto.FieldDtos;
import io.mosip.registration.processor.packet.storage.dto.FieldResponseDto;
import io.mosip.registration.processor.packet.storage.dto.InfoDto;
import io.mosip.registration.processor.packet.storage.dto.InfoRequestDto;
import io.mosip.registration.processor.packet.storage.dto.InfoResponseDto;
import io.mosip.registration.processor.packet.storage.dto.UpdateTagRequestDto;
import io.mosip.registration.processor.packet.storage.dto.ValidatePacketResponse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils2;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.common.rest.dto.ErrorDTO;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.exception.PacketManagerNonRecoverableException;
import io.mosip.registration.processor.core.http.RequestWrapper;
import io.mosip.registration.processor.core.http.ResponseWrapper;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.packet.storage.exception.ObjectDoesnotExistsException;

@Component
public class PacketManagerService {

    private static final Logger log = RegProcessorLogger.getLogger(PacketManagerService.class);

    private static final String ID = "mosip.commmons.packetmanager";
    private static final String VERSION = "v1";
    private static final String ERR_NOT_EXISTS = "KER-PUT-027";
    private static final List<String> NON_RECOVERABLE =
            Arrays.asList("KER-PUT-019");

    @Autowired
    private RegistrationProcessorRestClientService<Object> restApi;

    @Autowired
    private ObjectMapper mapper;

    @PostConstruct
    private void configureMapper() {
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /* =======================================================================
       CORE FAST-PATH HELPERS
       ======================================================================= */

    private void failFast(String method, String id, ErrorDTO err) throws PacketManagerException {
        String code = err.getErrorCode();
        String msg = err.getMessage();

        // ultra-light log (no JSON conversion)
        log.error(
                LoggerFileConstant.SESSIONID.toString(),
                LoggerFileConstant.REGISTRATIONID.toString(),
                id,
                "PacketManagerService." + method + " FAILED :: code=" + code + ", msg=" + msg
        );

        if (ERR_NOT_EXISTS.equals(code))
            throw new ObjectDoesnotExistsException(code, msg);

        if (NON_RECOVERABLE.contains(code))
            throw new PacketManagerNonRecoverableException(code, msg);

        throw new PacketManagerException(code, msg);
    }

    private void checkErrors(String method, String id, List<ErrorDTO> errors) throws PacketManagerException {
        if (errors != null && !errors.isEmpty())
            failFast(method, id, errors.get(0));
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

    /* =======================================================================
       FIELDS
       ======================================================================= */

    public String getField(String id, String field, String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        FieldDto dto = new FieldDto(id, field, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                (ResponseWrapper<FieldResponseDto>) restApi.postApi(
                        ApiName.PACKETMANAGER_SEARCH_FIELD, "", "", req(dto), ResponseWrapper.class);

        checkErrors("getField", id, resp.getErrors());

        FieldResponseDto f = convert(resp.getResponse(), FieldResponseDto.class);
        if (f == null || f.getFields() == null) return null;

        String v = f.getFields().get(field);
        return (v != null && "null".equalsIgnoreCase(v)) ? null : v;
    }

    public Map<String, String> getFields(String id, List<String> fields, String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        FieldDtos dto = new FieldDtos(id, fields, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                (ResponseWrapper<FieldResponseDto>) restApi.postApi(
                        ApiName.PACKETMANAGER_SEARCH_FIELDS, "", "", req(dto), ResponseWrapper.class);

        checkErrors("getFields", id, resp.getErrors());

        return convert(resp.getResponse(), FieldResponseDto.class).getFields();
    }

    /* =======================================================================
       DOCUMENT
       ======================================================================= */

    public io.mosip.registration.processor.packet.storage.dto.Document
    getDocument(String id, String documentName, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {
        return getDocument(id, documentName, null, process);
    }

    public io.mosip.registration.processor.packet.storage.dto.Document
    getDocument(String id, String documentName, String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        DocumentDto dto = new DocumentDto(id, documentName, source, process);

        @SuppressWarnings("unchecked")
        ResponseWrapper<io.mosip.registration.processor.packet.storage.dto.Document> resp =
                (ResponseWrapper<io.mosip.registration.processor.packet.storage.dto.Document>)
                        restApi.postApi(
                                ApiName.PACKETMANAGER_SEARCH_DOCUMENT,
                                "",
                                "",
                                req(dto),
                                ResponseWrapper.class
                        );

        checkErrors("getDocument", id, resp.getErrors());

        return convert(resp.getResponse(), io.mosip.registration.processor.packet.storage.dto.Document.class);
    }


    /* =======================================================================
       VALIDATE
       ======================================================================= */

    public ValidatePacketResponse validate(String id, String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        InfoDto dto = new InfoDto(id, source, process, false);

        ResponseWrapper<ValidatePacketResponse> resp =
                (ResponseWrapper<ValidatePacketResponse>) restApi.postApi(
                        ApiName.PACKETMANAGER_VALIDATE, "", "", req(dto), ResponseWrapper.class);

        checkErrors("validate", id, resp.getErrors());

        return convert(resp.getResponse(), ValidatePacketResponse.class);
    }

    /* =======================================================================
       AUDITS
       ======================================================================= */

    public List<FieldResponseDto> getAudits(String id, String src, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        InfoDto dto = new InfoDto(id, src, process, false);

        ResponseWrapper<List<Object>> resp =
                (ResponseWrapper<List<Object>>) restApi.postApi(
                        ApiName.PACKETMANAGER_SEARCH_AUDITS, "", "", req(dto), ResponseWrapper.class);

        checkErrors("getAudits", id, resp.getErrors());

        List<Object> raw = resp.getResponse();
        if (raw == null || raw.isEmpty()) return new ArrayList<>(0);

        int size = raw.size();
        List<FieldResponseDto> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++)
            list.add(convert(raw.get(i), FieldResponseDto.class));

        return list;
    }

    /* =======================================================================
       BIOMETRICS
       ======================================================================= */

    public BiometricRecord getBiometrics(String id, String person, List<String> mods,
                                         String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        BiometricRequestDto dto = new BiometricRequestDto(id, person, mods, source, process, false);

        ResponseWrapper<BiometricRecord> resp =
                (ResponseWrapper<BiometricRecord>) restApi.postApi(
                        ApiName.PACKETMANAGER_SEARCH_BIOMETRICS, "", "", req(dto), ResponseWrapper.class);

        checkErrors("getBiometrics", id, resp.getErrors());

        return convert(resp.getResponse(), BiometricRecord.class);
    }

    /* =======================================================================
       META INFO
       ======================================================================= */

    public Map<String, String> getMetaInfo(String id, String source, String process)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        InfoDto dto = new InfoDto(id, source, process, false);

        ResponseWrapper<FieldResponseDto> resp =
                (ResponseWrapper<FieldResponseDto>) restApi.postApi(
                        ApiName.PACKETMANAGER_SEARCH_METAINFO, "", "", req(dto), ResponseWrapper.class);

        checkErrors("getMetaInfo", id, resp.getErrors());

        return convert(resp.getResponse(), FieldResponseDto.class).getFields();
    }

    /* =======================================================================
       INFO
       ======================================================================= */

    public InfoResponseDto info(String id)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        InfoRequestDto dto = new InfoRequestDto(id);

        ResponseWrapper<InfoResponseDto> resp =
                (ResponseWrapper<InfoResponseDto>) restApi.postApi(
                        ApiName.PACKETMANAGER_INFO, "", "", req(dto), ResponseWrapper.class);

        checkErrors("info", id, resp.getErrors());

        return convert(resp.getResponse(), InfoResponseDto.class);
    }

    /* =======================================================================
       TAGS
       ======================================================================= */

    public void addOrUpdateTags(String id, Map<String, String> tags)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        UpdateTagRequestDto dto = new UpdateTagRequestDto(id, tags);

        ResponseWrapper<Void> resp =
                (ResponseWrapper<Void>) restApi.postApi(
                        ApiName.PACKETMANAGER_UPDATE_TAGS, "", "", req(dto), ResponseWrapper.class);

        checkErrors("addOrUpdateTags", id, resp.getErrors());
    }

    public void deleteTags(String id, List<String> tags)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException {

        DeleteTagRequestDTO dto = new DeleteTagRequestDTO(id, tags);

        ResponseWrapper<DeleteTagResponseDTO> resp =
                (ResponseWrapper<DeleteTagResponseDTO>) restApi.postApi(
                        ApiName.PACKETMANAGER_DELETE_TAGS, "", "", req(dto), ResponseWrapper.class);

        checkErrors("deleteTags", id, resp.getErrors());
    }

    public Map<String, String> getAllTags(String id)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {
        return getTags(id, null);
    }

    public Map<String, String> getTags(String id, List<String> tagNames)
            throws ApisResourceAccessException, PacketManagerException, JsonProcessingException, IOException {

        TagRequestDto dto = new TagRequestDto(id, tagNames);

        ResponseWrapper<TagResponseDto> resp =
                (ResponseWrapper<TagResponseDto>) restApi.postApi(
                        ApiName.PACKETMANAGER_GET_TAGS, "", "", req(dto), ResponseWrapper.class);

        List<ErrorDTO> errors = resp.getErrors();
        if (errors != null && !errors.isEmpty()) {
            ErrorDTO e = errors.get(0);
            if ("KER-PUT-024".equalsIgnoreCase(e.getErrorCode())) return null; // original behavior
            failFast("getTags", id, e);
        }

        TagResponseDto out = convert(resp.getResponse(), TagResponseDto.class);
        return (out != null) ? out.getTags() : null;
    }
}
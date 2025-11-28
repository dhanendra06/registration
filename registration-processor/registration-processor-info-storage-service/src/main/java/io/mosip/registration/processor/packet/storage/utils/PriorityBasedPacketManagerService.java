package io.mosip.registration.processor.packet.storage.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.assertj.core.util.Lists;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.kernel.core.util.exception.JsonProcessingException;
import io.mosip.registration.processor.core.constant.MappingJsonConstants;
import io.mosip.registration.processor.core.constant.ProviderStageName;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.packet.storage.dto.ContainerInfoDto;
import io.mosip.registration.processor.packet.storage.dto.Document;
import io.mosip.registration.processor.packet.storage.dto.FieldResponseDto;
import io.mosip.registration.processor.packet.storage.dto.InfoResponseDto;
import io.mosip.registration.processor.packet.storage.dto.ValidatePacketResponse;
import io.mosip.registration.processor.packet.storage.helper.PacketManagerHelper;

@Component
public class PriorityBasedPacketManagerService {

    @Autowired
    private Utilities utilities;

    @Autowired
    private PacketManagerHelper packetManagerHelper;

    @Autowired
    private PacketManagerService packetManagerService;

    private static Map<String, String> providerConfiguration;

    public static void initialize(Map<String, String> provider) {
        providerConfiguration = provider;
    }

    /**
     * Get fields by mapping json Constant key.
     */
    public String getFieldByMappingJsonKey(String id, String key, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        JSONObject regProcessorIdentityJson =
                utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        String field = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(regProcessorIdentityJson, key),
                MappingJsonConstants.VALUE);

        return getField(id, field, process, stageName);
    }

    /**
     * Get all fields by mapping json keys (single shot).
     */
    public Map<String, String> getAllFieldsByMappingJsonKeys(String id, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        JSONObject regProcessorIdentityJson =
                utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        List<String> fields = new ArrayList<>();
        for (Object key : regProcessorIdentityJson.keySet()) {
            String field = JsonUtil.getJSONValue(
                    JsonUtil.getJSONObject(regProcessorIdentityJson, key),
                    MappingJsonConstants.VALUE);
            // handle comma-separated mapping values
            for (String f : field.split(",")) {
                fields.add(f.trim());
            }
        }

        return getFields(id, fields, process, stageName);
    }

    /**
     * Get single field by priority set in configuration
     * (delegates to getFields to reuse batching).
     */
    public String getField(String id, String field, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        Map<String, String> fieldMap =
                getFields(id, Lists.newArrayList(field), process, stageName);

        return (fieldMap != null && fieldMap.size() == 1)
                ? fieldMap.values().iterator().next()
                : null;
    }

    /**
     * Get fields by priority set in configuration.
     * This now minimizes remote calls by batching fields per container.
     */
    public Map<String, String> getFields(String id, List<String> fields, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        List<String> priorityList = new ArrayList<>();
        List<String> nonPriorityList = new ArrayList<>();
        Map<String, String> fieldMap = new HashMap<>();

        Map<String, String> keyMap = PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        // Split fields into priority vs non-priority based on configuration
        if (!CollectionUtils.isEmpty(keyMap)) {
            for (String field : fields) {
                if (packetManagerHelper.isFieldPresent(field, stageName, providerConfiguration)) {
                    priorityList.add(field);
                } else {
                    nonPriorityList.add(field);
                }
            }
        } else {
            nonPriorityList.addAll(fields);
        }

        // Priority fields (optimized batching)
        if (!CollectionUtils.isEmpty(priorityList)) {
            fieldMap.putAll(getFieldsByPriority(id, stageName, priorityList));
        }

        // Non-priority fields → simple single call
        if (!CollectionUtils.isEmpty(nonPriorityList)) {
            fieldMap.putAll(packetManagerService.getFields(id, nonPriorityList, null, process));
        }

        return fieldMap;
    }

    /**
     * Get meta info by priority.
     */
    public Map<String, String> getMetaInfo(String id, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.METAINFO, stageName);

        return (containerInfoDto != null)
                ? packetManagerService.getMetaInfo(id, containerInfoDto.getSource(), containerInfoDto.getProcess())
                : packetManagerService.getMetaInfo(id, null, process);
    }

    /**
     * Get document by priority.
     */
    public Document getDocument(String id, String documentName, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, documentName, stageName);

        return (containerInfoDto != null)
                ? packetManagerService.getDocument(id, documentName,
                containerInfoDto.getSource(),
                containerInfoDto.getProcess())
                : packetManagerService.getDocument(id, documentName, process);
    }

    /**
     * Validate packet by priority.
     */
    public ValidatePacketResponse validate(String id, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.VALIDATE, stageName);

        return (containerInfoDto != null)
                ? packetManagerService.validate(id, containerInfoDto.getSource(), containerInfoDto.getProcess())
                : packetManagerService.validate(id, null, process);
    }

    /**
     * Get audits by priority.
     */
    public List<FieldResponseDto> getAudits(String id, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.AUDITS, stageName);

        return (containerInfoDto != null)
                ? packetManagerService.getAudits(id, containerInfoDto.getSource(), containerInfoDto.getProcess())
                : packetManagerService.getAudits(id, null, process);
    }

    /**
     * Get biometrics by priority with mapping json key as input.
     */
    public BiometricRecord getBiometricsByMappingJsonKey(String id,
                                                         String mappingJsonKey,
                                                         String process,
                                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        String biometricLabel = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(
                        utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY),
                        mappingJsonKey),
                MappingJsonConstants.VALUE);

        return getBiometrics(id, biometricLabel, process, stageName);
    }

    /**
     * Get biometrics by priority (no modalities filter).
     */
    public BiometricRecord getBiometrics(String id,
                                         String person,
                                         String process,
                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        return getBiometricsInternal(id, person, null, process, stageName);
    }

    /**
     * Get biometrics by priority (with modalities filter).
     */
    public BiometricRecord getBiometrics(String id,
                                         String person,
                                         List<String> modalities,
                                         String process,
                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        return getBiometricsInternal(id, person, modalities, process, stageName);
    }

    /* =====================================================================
       INTERNAL BIOMETRIC LOGIC
       ===================================================================== */

    private BiometricRecord getBiometricsInternal(String id,
                                                  String person,
                                                  List<String> modalities,
                                                  String process,
                                                  ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> baseKeyMap =
                PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        Map<String, String> finalKeyMap = baseKeyMap.isEmpty()
                ? null
                : baseKeyMap.entrySet().stream()
                .filter(entry -> entry.getKey().contains(person))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // No priority set → default behavior
        if (CollectionUtils.isEmpty(finalKeyMap)) {
            return packetManagerService.getBiometrics(id, person, modalities, null, process);
        }

        InfoResponseDto infoResponseDto = packetManagerService.info(id);

        // Single config entry for this person (fast path)
        if (finalKeyMap.get(person) != null) {
            ContainerInfoDto containerInfoDto =
                    PacketManagerHelper.getContainerInfo(finalKeyMap, person, infoResponseDto);
            List<String> containerModalities =
                    CollectionUtils.isEmpty(modalities)
                            ? PacketManagerHelper.getTypeSubtypeModalities(containerInfoDto)
                            : modalities;

            return packetManagerService.getBiometrics(
                    id, person, containerModalities,
                    containerInfoDto.getSource(), containerInfoDto.getProcess());
        }

        // Multiple containers, merge segments
        Set<ContainerInfoDto> containers = new HashSet<>();
        BiometricRecord biometricRecord = null;

        for (String key : finalKeyMap.keySet()) {
            ContainerInfoDto containerInfoDto =
                    PacketManagerHelper.getBiometricContainerInfo(finalKeyMap, person, key, infoResponseDto);
            if (containerInfoDto != null) {
                Optional<Boolean> optional =
                        containers.stream().map(c -> c.equals(containerInfoDto)).findAny();
                if (!optional.isPresent() || Boolean.FALSE.equals(optional.get())) {
                    containers.add(containerInfoDto);
                }
            }
        }

        for (ContainerInfoDto containerInfoDto : containers) {
            List<String> containerModalities =
                    CollectionUtils.isEmpty(modalities)
                            ? PacketManagerHelper.getTypeSubtypeModalities(containerInfoDto)
                            : modalities;

            BiometricRecord record = packetManagerService.getBiometrics(
                    id, person, containerModalities,
                    containerInfoDto.getSource(), containerInfoDto.getProcess());

            if (record != null && record.getSegments() != null) {
                if (biometricRecord == null) {
                    biometricRecord = new BiometricRecord();
                    biometricRecord.setSegments(new ArrayList<>());
                }
                biometricRecord.getSegments().addAll(record.getSegments());
            }
        }

        return biometricRecord;
    }

    /* =====================================================================
       PRIORITY FIELD HANDLING (OPTIMIZED)
       ===================================================================== */

    private Map<String, String> getFieldsByPriority(String id,
                                                    ProviderStageName stageName,
                                                    List<String> fields)
            throws ApisResourceAccessException, IOException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> fieldMap = new HashMap<>();
        InfoResponseDto infoResponseDto = packetManagerService.info(id);

        // If there is only one source/container → reuse it for all fields (fast path)
        if (infoResponseDto.getInfo().size() == 1) {
            ContainerInfoDto containerInfoDto = infoResponseDto.getInfo().iterator().next();

            // Single batched call for all fields
            Map<String, String> response =
                    packetManagerService.getFields(
                            id,
                            fields,
                            containerInfoDto.getSource(),
                            containerInfoDto.getProcess());

            if (response != null) {
                for (String field : fields) {
                    String val = response.get(field);
                    // preserve behavior of getField: "null" string → null
                    if (val != null && "null".equalsIgnoreCase(val)) {
                        val = null;
                    }
                    fieldMap.put(field, val);
                }
            }
            return fieldMap;
        }

        // Multiple sources → group fields by container to minimize remote calls
        Map<String, String> keyMap = PacketManagerHelper.getKeyMap(stageName, providerConfiguration);
        if (CollectionUtils.isEmpty(keyMap)) {
            // safety: if no key map, nothing to resolve here
            return fieldMap;
        }

        // Group fields by (source,process) container
        Map<ContainerInfoDto, List<String>> fieldsByContainer = new HashMap<>();

        for (String field : fields) {
            ContainerInfoDto containerInfoDto =
                    PacketManagerHelper.getContainerInfo(keyMap, field, infoResponseDto);

            if (containerInfoDto != null) {
                fieldsByContainer
                        .computeIfAbsent(containerInfoDto, k -> new ArrayList<>())
                        .add(field);
            }
        }

        // For each container, fetch all its fields in a single call
        for (Map.Entry<ContainerInfoDto, List<String>> entry : fieldsByContainer.entrySet()) {
            ContainerInfoDto containerInfoDto = entry.getKey();
            List<String> containerFields = entry.getValue();

            Map<String, String> response =
                    packetManagerService.getFields(
                            id,
                            containerFields,
                            containerInfoDto.getSource(),
                            containerInfoDto.getProcess());

            if (response != null) {
                for (String field : containerFields) {
                    String val = response.get(field);
                    // preserve getField's "null" → null normalization
                    if (val != null && "null".equalsIgnoreCase(val)) {
                        val = null;
                    }
                    fieldMap.put(field, val);
                }
            }
        }

        return fieldMap;
    }

    /* =====================================================================
       PRIORITY RESOLUTION: SOURCE / PROCESS
       ===================================================================== */

    private ContainerInfoDto findSourceAndProcessByPriority(String id,
                                                            String field,
                                                            ProviderStageName stageName)
            throws ApisResourceAccessException, IOException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> keyMap = PacketManagerHelper.getKeyMap(stageName, providerConfiguration);
        if (keyMap != null && keyMap.get(field) != null) {
            InfoResponseDto infoResponseDto = packetManagerService.info(id);
            return PacketManagerHelper.getContainerInfo(keyMap, field, infoResponseDto);
        }
        return null;
    }
}

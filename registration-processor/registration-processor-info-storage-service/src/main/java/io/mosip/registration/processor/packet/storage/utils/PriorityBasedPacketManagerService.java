package io.mosip.registration.processor.packet.storage.utils;

import java.io.IOException;
import java.util.*;
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

    /**
     * Global provider configuration –
     * initialized from outside, same as original.
     */
    private static Map<String, String> providerConfiguration;

    public static void initialize(Map<String, String> provider) {
        providerConfiguration = provider;
    }

    private static Map<String, String> getProviderConfiguration() {
        return providerConfiguration == null ? Collections.emptyMap() : providerConfiguration;
    }

    /* ====================================================================
       FIELD ACCESS (BY MAPPING JSON)
       ==================================================================== */

    public String getFieldByMappingJsonKey(String id, String key, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        JSONObject regProcessorIdentityJson =
                utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        String field = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(regProcessorIdentityJson, key),
                MappingJsonConstants.VALUE
        );

        return getField(id, field, process, stageName);
    }

    public Map<String, String> getAllFieldsByMappingJsonKeys(String id, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        JSONObject regProcessorIdentityJson =
                utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        List<String> fields = new ArrayList<>();
        for (Object key : regProcessorIdentityJson.keySet()) {
            String field = JsonUtil.getJSONValue(
                    JsonUtil.getJSONObject(regProcessorIdentityJson, key),
                    MappingJsonConstants.VALUE
            );
            // preserve original split logic
            fields.addAll(Arrays.asList(field.split(",")));
        }

        return getFields(id, fields, process, stageName);
    }

    public String getField(String id, String field, String process, ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        Map<String, String> fieldMap =
                getFields(id, Lists.newArrayList(field), process, stageName);

        return (fieldMap != null && fieldMap.size() == 1)
                ? fieldMap.values().iterator().next()
                : null;
    }

    public Map<String, String> getFields(String id,
                                         List<String> fields,
                                         String process,
                                         ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        if (CollectionUtils.isEmpty(fields)) {
            return Collections.emptyMap();
        }

        Map<String, String> fieldMap = new HashMap<>(fields.size());
        List<String> priorityList = new ArrayList<>();
        List<String> nonPriorityList = new ArrayList<>();

        Map<String, String> keyMap = PacketManagerHelper.getKeyMap(stageName, getProviderConfiguration());

        // Partition into fields with priority and those without
        if (!keyMap.isEmpty()) {
            for (String field : fields) {
                if (packetManagerHelper.isFieldPresent(field, stageName, getProviderConfiguration())) {
                    priorityList.add(field);
                } else {
                    nonPriorityList.add(field);
                }
            }
        } else {
            nonPriorityList.addAll(fields);
        }

        // Priority fields – use priority algorithm (may change source/process)
        if (!priorityList.isEmpty()) {
            fieldMap.putAll(getFieldsByPriority(id, stageName, priorityList));
        }

        // Non-priority fields – direct multi-field call, single source+process (null, process)
        if (!nonPriorityList.isEmpty()) {
            fieldMap.putAll(
                    packetManagerService.getFields(id, nonPriorityList, null, process)
            );
        }

        return fieldMap;
    }

    /* ====================================================================
       META INFO / DOCUMENT / VALIDATE / AUDITS
       ==================================================================== */

    public Map<String, String> getMetaInfo(String id,
                                           String process,
                                           ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.METAINFO, stageName);

        if (containerInfoDto != null) {
            return packetManagerService.getMetaInfo(
                    id,
                    containerInfoDto.getSource(),
                    containerInfoDto.getProcess()
            );
        }

        return packetManagerService.getMetaInfo(id, null, process);
    }

    public Document getDocument(String id,
                                String documentName,
                                String process,
                                ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, documentName, stageName);

        if (containerInfoDto == null) {
            return packetManagerService.getDocument(id, documentName, process);
        }

        return packetManagerService.getDocument(
                id,
                documentName,
                containerInfoDto.getSource(),
                containerInfoDto.getProcess()
        );
    }

    public ValidatePacketResponse validate(String id,
                                           String process,
                                           ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.VALIDATE, stageName);

        if (containerInfoDto == null) {
            return packetManagerService.validate(id, null, process);
        }

        return packetManagerService.validate(
                id,
                containerInfoDto.getSource(),
                containerInfoDto.getProcess()
        );
    }

    public List<FieldResponseDto> getAudits(String id,
                                            String process,
                                            ProviderStageName stageName)
            throws ApisResourceAccessException, PacketManagerException,
            JsonProcessingException, IOException {

        ContainerInfoDto containerInfoDto =
                findSourceAndProcessByPriority(id, MappingJsonConstants.AUDITS, stageName);

        if (containerInfoDto == null) {
            return packetManagerService.getAudits(id, null, process);
        }

        return packetManagerService.getAudits(
                id,
                containerInfoDto.getSource(),
                containerInfoDto.getProcess()
        );
    }

    /* ====================================================================
       BIOMETRICS
       ==================================================================== */

    public BiometricRecord getBiometricsByMappingJsonKey(String id,
                                                         String mappingJsonKey,
                                                         String process,
                                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        String biometricLabel = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(
                        utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY),
                        mappingJsonKey
                ),
                MappingJsonConstants.VALUE
        );

        return getBiometrics(id, biometricLabel, process, stageName);
    }

    public BiometricRecord getBiometrics(String id,
                                         String person,
                                         String process,
                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        return getBiometricsInternal(id, person, null, process, stageName);
    }

    public BiometricRecord getBiometrics(String id,
                                         String person,
                                         List<String> modalities,
                                         String process,
                                         ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        return getBiometricsInternal(id, person, modalities, process, stageName);
    }

    private BiometricRecord getBiometricsInternal(String id,
                                                  String person,
                                                  List<String> modalities,
                                                  String process,
                                                  ProviderStageName stageName)
            throws IOException, ApisResourceAccessException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, getProviderConfiguration());

        Map<String, String> finalKeyMap;

        if (keyMap.isEmpty()) {
            finalKeyMap = Collections.emptyMap();
        } else {
            finalKeyMap = keyMap.entrySet()
                    .stream()
                    .filter(e -> e.getKey().contains(person))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        // No priority configured for this person → direct call
        if (finalKeyMap.isEmpty()) {
            return packetManagerService.getBiometrics(id, person, modalities, null, process);
        }

        InfoResponseDto infoResponseDto = packetManagerService.info(id);

        // If an exact person key exists, use container info directly (single call)
        if (finalKeyMap.get(person) != null) {
            ContainerInfoDto containerInfoDto =
                    PacketManagerHelper.getContainerInfo(finalKeyMap, person, infoResponseDto);

            List<String> effectiveModalities = modalities;
            if (CollectionUtils.isEmpty(effectiveModalities)) {
                effectiveModalities = PacketManagerHelper.getTypeSubtypeModalities(containerInfoDto);
            }

            return packetManagerService.getBiometrics(
                    id,
                    person,
                    effectiveModalities,
                    containerInfoDto.getSource(),
                    containerInfoDto.getProcess()
            );
        }

        // Else, aggregate biometrics from multiple containers
        Set<ContainerInfoDto> containers = new HashSet<>();
        for (String key : finalKeyMap.keySet()) {
            ContainerInfoDto containerInfoDto =
                    PacketManagerHelper.getBiometricContainerInfo(finalKeyMap, person, key, infoResponseDto);
            if (containerInfoDto != null) {
                containers.add(containerInfoDto); // Set + proper equals/hashCode avoids duplicates
            }
        }

        if (containers.isEmpty()) {
            return null;
        }

        BiometricRecord biometricRecord = null;
        for (ContainerInfoDto containerInfoDto : containers) {
            List<String> containerModalities = modalities;
            if (CollectionUtils.isEmpty(containerModalities)) {
                containerModalities = PacketManagerHelper.getTypeSubtypeModalities(containerInfoDto);
            }

            BiometricRecord record = packetManagerService.getBiometrics(
                    id,
                    person,
                    containerModalities,
                    containerInfoDto.getSource(),
                    containerInfoDto.getProcess()
            );

            if (record != null && record.getSegments() != null && !record.getSegments().isEmpty()) {
                if (biometricRecord == null) {
                    biometricRecord = new BiometricRecord();
                    biometricRecord.setSegments(new ArrayList<>());
                }
                biometricRecord.getSegments().addAll(record.getSegments());
            }
        }

        return biometricRecord;
    }

    /* ====================================================================
       INTERNAL HELPERS
       ==================================================================== */

    /**
     * Strong optimization over the original:
     * - Still preserves behavior
     * - Groups fields by ContainerInfo so we can call getFields() per container
     *   instead of getField() per field (reducing external calls).
     */
    private Map<String, String> getFieldsByPriority(String id,
                                                    ProviderStageName stageName,
                                                    List<String> fields)
            throws ApisResourceAccessException, IOException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> fieldMap = new HashMap<>(fields.size());

        InfoResponseDto infoResponseDto = packetManagerService.info(id);
        Collection<ContainerInfoDto> infos = infoResponseDto.getInfo();

        if (infos == null || infos.isEmpty()) {
            return fieldMap;
        }

        // Fast-path: only one source → one call for all fields
        if (infos.size() == 1) {
            ContainerInfoDto containerInfoDto = infos.iterator().next();
            fieldMap.putAll(
                    packetManagerService.getFields(
                            id,
                            fields,
                            containerInfoDto.getSource(),
                            containerInfoDto.getProcess()
                    )
            );
        } else {
            // Multi-source: group fields by ContainerInfo and call getFields per container
            Map<String, String> keyMap =
                    PacketManagerHelper.getKeyMap(stageName, getProviderConfiguration());

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

            // Execute batched external calls
            for (Map.Entry<ContainerInfoDto, List<String>> entry : fieldsByContainer.entrySet()) {
                ContainerInfoDto c = entry.getKey();
                List<String> containerFields = entry.getValue();

                Map<String, String> values =
                        packetManagerService.getFields(
                                id,
                                containerFields,
                                c.getSource(),
                                c.getProcess()
                        );
                if (values != null) {
                    fieldMap.putAll(values);
                }
            }
        }

        // Preserve existing additional logic for applicant biometric label
        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, getProviderConfiguration());

        List<ContainerInfoDto> infoList = new ArrayList<>(infoResponseDto.getInfo());
        ContainerInfoDto containerInfoDto =
                packetManagerHelper.getBiometricSourceAndProcess(fields, keyMap, infoList);

        if (containerInfoDto != null) {
            String label = packetManagerHelper.getApplicantBiometricLabel();
            String labelValue = packetManagerService.getField(
                    id,
                    label,
                    containerInfoDto.getSource(),
                    containerInfoDto.getProcess()
            );
            fieldMap.put(label, labelValue);
        }

        return fieldMap;
    }

    private ContainerInfoDto findSourceAndProcessByPriority(String id,
                                                            String field,
                                                            ProviderStageName stageName)
            throws ApisResourceAccessException, IOException,
            PacketManagerException, JsonProcessingException {

        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, getProviderConfiguration());

        if (keyMap != null && keyMap.get(field) != null) {
            InfoResponseDto infoResponseDto = packetManagerService.info(id);
            return PacketManagerHelper.getContainerInfo(keyMap, field, infoResponseDto);
        }
        return null;
    }
}

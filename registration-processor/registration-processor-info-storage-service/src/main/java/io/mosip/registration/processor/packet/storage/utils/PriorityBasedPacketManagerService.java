package io.mosip.registration.processor.packet.storage.utils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.PacketManagerException;
import io.mosip.registration.processor.packet.storage.helper.PacketManagerHelper;
import org.assertj.core.util.Lists;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.mosip.kernel.biometrics.entities.BiometricRecord;
import io.mosip.registration.processor.core.constant.MappingJsonConstants;
import io.mosip.registration.processor.core.constant.ProviderStageName;

import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.packet.storage.dto.*;

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

    /* ============================================================
       FIELDS (safe)
       ============================================================ */

    public String getFieldByMappingJsonKey(String id, String key, String process, ProviderStageName stageName) throws IOException, PacketManagerException, ApisResourceAccessException {

        JSONObject identityJson = utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        String field = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(identityJson, key),
                MappingJsonConstants.VALUE
        );

        return getField(id, field, process, stageName);
    }


    public Map<String, String> getAllFieldsByMappingJsonKeys(String id, String process, ProviderStageName stageName) throws IOException, PacketManagerException, ApisResourceAccessException {

        JSONObject identityJson = utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        List<String> fields = new ArrayList<>();
        for (Object key : identityJson.keySet()) {

            String field = JsonUtil.getJSONValue(
                    JsonUtil.getJSONObject(identityJson, key),
                    MappingJsonConstants.VALUE
            );

            for (String f : field.split(",")) {
                fields.add(f.trim());
            }
        }

        return getFields(id, fields, process, stageName);
    }


    public String getField(String id, String field, String process, ProviderStageName stageName) throws PacketManagerException, IOException, ApisResourceAccessException {

        Map<String, String> map =
                getFields(id, Lists.newArrayList(field), process, stageName);

        if (map == null || map.isEmpty()) return null;

        String val = map.get(field);
        return ("null".equalsIgnoreCase(val)) ? null : val;
    }


    public Map<String, String> getFields(String id, List<String> fields, String process, ProviderStageName stageName) throws IOException, PacketManagerException, ApisResourceAccessException {

        if (CollectionUtils.isEmpty(fields))
            return Collections.emptyMap();

        Map<String, String> result = new HashMap<>();

        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        List<String> priority = new ArrayList<>();
        List<String> nonPriority = new ArrayList<>();

        if (!CollectionUtils.isEmpty(keyMap)) {
            for (String f : fields) {
                if (packetManagerHelper.isFieldPresent(f, stageName, providerConfiguration))
                    priority.add(f);
                else
                    nonPriority.add(f);
            }
        } else {
            nonPriority.addAll(fields);
        }

        // batched priority
        if (!priority.isEmpty()) {
            result.putAll(getFieldsByPriority(id, stageName, priority));
        }

        // non-priority direct call
        if (!nonPriority.isEmpty()) {
            result.putAll(packetManagerService.getFields(id, nonPriority, null, process));
        }

        return result;
    }


    /* ============================================================
       META INFO / DOCUMENT / VALIDATE / AUDITS
       ============================================================ */

    public Map<String, String> getMetaInfo(String id, String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        ContainerInfoDto container = findContainer(id, MappingJsonConstants.METAINFO, stageName);

        return (container != null)
                ? packetManagerService.getMetaInfo(id, container.getSource(), container.getProcess())
                : packetManagerService.getMetaInfo(id, null, process);
    }


    public Document getDocument(String id, String documentName, String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        ContainerInfoDto container = findContainer(id, documentName, stageName);

        return (container != null)
                ? packetManagerService.getDocument(id, documentName, container.getSource(), container.getProcess())
                : packetManagerService.getDocument(id, documentName, process);
    }


    public ValidatePacketResponse validate(String id, String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        ContainerInfoDto container = findContainer(id, MappingJsonConstants.VALIDATE, stageName);

        return (container != null)
                ? packetManagerService.validate(id, container.getSource(), container.getProcess())
                : packetManagerService.validate(id, null, process);
    }


    public List<FieldResponseDto> getAudits(String id, String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        ContainerInfoDto container = findContainer(id, MappingJsonConstants.AUDITS, stageName);

        return (container != null)
                ? packetManagerService.getAudits(id, container.getSource(), container.getProcess())
                : packetManagerService.getAudits(id, null, process);
    }

    /* ============================================================
       BIOMETRICS
       ============================================================ */

    public BiometricRecord getBiometricsByMappingJsonKey(String id, String key, String process, ProviderStageName stageName) throws IOException, PacketManagerException, ApisResourceAccessException {

        JSONObject identity = utilities.getRegistrationProcessorMappingJson(MappingJsonConstants.IDENTITY);

        String label = JsonUtil.getJSONValue(
                JsonUtil.getJSONObject(identity, key),
                MappingJsonConstants.VALUE
        );

        return getBiometrics(id, label, process, stageName);
    }


    public BiometricRecord getBiometrics(String id, String person, String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        return getBiometrics(id, person, null, process, stageName);
    }


    public BiometricRecord getBiometrics(String id, String person, List<String> mods, String process,
                                         ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        return getBiometricsInternal(id, person, mods, process, stageName);
    }


    private BiometricRecord getBiometricsInternal(String id, String person, List<String> mods,
                                                  String process, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        Map<String, String> raw = PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        if (raw == null || raw.isEmpty())
            return packetManagerService.getBiometrics(id, person, mods, null, process);

        Map<String, String> finalMap =
                raw.entrySet().stream()
                        .filter(e -> e.getKey().contains(person))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (finalMap.isEmpty())
            return packetManagerService.getBiometrics(id, person, mods, null, process);

        InfoResponseDto info = packetManagerService.info(id);

        // single source shortcut
        if (finalMap.get(person) != null) {

            ContainerInfoDto container = PacketManagerHelper.getContainerInfo(finalMap, person, info);

            List<String> useMods =
                    CollectionUtils.isEmpty(mods)
                            ? PacketManagerHelper.getTypeSubtypeModalities(container)
                            : mods;

            return packetManagerService.getBiometrics(
                    id, person, useMods, container.getSource(), container.getProcess());
        }

        // multi-source merge
        BiometricRecord merged = null;
        Set<ContainerInfoDto> containers = new HashSet<>();

        for (String key : finalMap.keySet()) {
            ContainerInfoDto c =
                    PacketManagerHelper.getBiometricContainerInfo(finalMap, person, key, info);

            if (c != null) containers.add(c);
        }

        for (ContainerInfoDto c : containers) {

            List<String> useMods =
                    CollectionUtils.isEmpty(mods)
                            ? PacketManagerHelper.getTypeSubtypeModalities(c)
                            : mods;

            BiometricRecord rec =
                    packetManagerService.getBiometrics(id, person, useMods, c.getSource(), c.getProcess());

            if (rec != null && rec.getSegments() != null) {

                if (merged == null) {
                    merged = new BiometricRecord();
                    merged.setSegments(new ArrayList<>());
                }

                merged.getSegments().addAll(rec.getSegments());
            }
        }

        return merged;
    }

    /* ============================================================
       PRIORITY FIELDS (BATCHING)
       ============================================================ */

    private Map<String, String> getFieldsByPriority(String id, ProviderStageName stageName, List<String> fields) throws PacketManagerException, ApisResourceAccessException {

        Map<String, String> out = new HashMap<>();

        InfoResponseDto info = packetManagerService.info(id);

        // Only 1 container: fully optimized
        if (info.getInfo().size() == 1) {

            ContainerInfoDto c = info.getInfo().iterator().next();

            Map<String, String> map =
                    packetManagerService.getFields(id, fields, c.getSource(), c.getProcess());

            if (map != null) {
                for (String f : fields) {
                    String v = map.get(f);
                    out.put(f, (v != null && v.equalsIgnoreCase("null")) ? null : v);
                }
            }
            return out;
        }

        // multi-container: group by container
        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        if (CollectionUtils.isEmpty(keyMap))
            return out;

        Map<ContainerInfoDto, List<String>> grouped = new HashMap<>();

        for (String field : fields) {

            ContainerInfoDto c =
                    PacketManagerHelper.getContainerInfo(keyMap, field, info);

            if (c != null) {
                grouped.computeIfAbsent(c, k -> new ArrayList<>()).add(field);
            }
        }

        // batch per container
        for (var entry : grouped.entrySet()) {

            ContainerInfoDto c = entry.getKey();
            List<String> group = entry.getValue();

            Map<String, String> map =
                    packetManagerService.getFields(id, group, c.getSource(), c.getProcess());

            if (map != null) {
                for (String f : group) {
                    String v = map.get(f);
                    out.put(f, (v != null && v.equalsIgnoreCase("null")) ? null : v);
                }
            }
        }

        return out;
    }


    /* ============================================================
       RESOLVE PRIORITY CONTAINER
       ============================================================ */

    private ContainerInfoDto findContainer(String id, String field, ProviderStageName stageName) throws PacketManagerException, ApisResourceAccessException {

        Map<String, String> keyMap =
                PacketManagerHelper.getKeyMap(stageName, providerConfiguration);

        if (keyMap != null && keyMap.get(field) != null) {

            InfoResponseDto info = packetManagerService.info(id);

            return PacketManagerHelper.getContainerInfo(keyMap, field, info);
        }

        return null;
    }
}

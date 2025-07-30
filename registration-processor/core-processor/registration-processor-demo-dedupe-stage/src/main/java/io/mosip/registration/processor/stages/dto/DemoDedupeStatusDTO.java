package io.mosip.registration.processor.stages.dto;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import io.mosip.registration.processor.core.packet.dto.demographicinfo.DemographicInfoDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for holding demo dedupe status with improved performance considerations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DemoDedupeStatusDTO implements Serializable {

    /**
     * Indicates whether the dedupe transaction was successful.
     */
    private boolean transactionSuccessful;

    /**
     * Contains list of potential duplicate demographic records.
     */
    private List<DemographicInfoDto> duplicateDtos = Collections.emptyList();
}
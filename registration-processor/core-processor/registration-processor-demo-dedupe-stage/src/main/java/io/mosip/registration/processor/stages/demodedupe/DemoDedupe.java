package io.mosip.registration.processor.stages.demodedupe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.demographicinfo.DemographicInfoDto;
import io.mosip.registration.processor.packet.storage.dao.PacketInfoDao;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * The Class DemoDedupe.
 *
 * @author M1048358 Alok Ranjan
 * @author M1048860 Kiran Raj
 */
@Component
public class DemoDedupe {

	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(DemoDedupe.class);

	/** The env. */
	@Autowired
	private Environment env;

	@Autowired
	private RegistrationStatusService registrationStatusService;

	/** The packet info dao. */
	@Autowired
	private PacketInfoDao packetInfoDao;

	 /**
     * Perform deduplication based on demographic data.
     *
     * @param refId reference ID of the registration
     * @return list of potential duplicate records with valid UIN
     */
    public List<DemographicInfoDto> performDedupe(String refId) {
        regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REFERENCEID.toString(), refId,
                "DemoDedupe::performDedupe()::entry");

        List<DemographicInfoDto> applicantDemoDto = packetInfoDao.findDemoById(refId);
        if (applicantDemoDto == null || applicantDemoDto.isEmpty()) {
            regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REFERENCEID.toString(), refId,
                    "No demographic data found for the provided refId.");
            return Collections.emptyList();
        }

        // Using a thread-safe queue to collect potential duplicates in parallel
        ConcurrentLinkedQueue<DemographicInfoDto> potentialDuplicates = new ConcurrentLinkedQueue<>();

        // Parallelize DB calls for faster dedupe processing
        applicantDemoDto.parallelStream().forEach(demoDto -> {
            List<DemographicInfoDto> matches = packetInfoDao.getAllDemographicInfoDtos(
                    demoDto.getName(),
                    demoDto.getGenderCode(),
                    demoDto.getDob(),
                    demoDto.getLangCode()
            );
            if (matches != null && !matches.isEmpty()) {
                potentialDuplicates.addAll(matches);
            }
        });

        // Filter records that have UIN available using parallel processing
        List<DemographicInfoDto> finalList = potentialDuplicates.parallelStream()
                .filter(Objects::nonNull)
                .filter(dto -> registrationStatusService.checkUinAvailabilityForRid(dto.getRegId()))
                .collect(Collectors.toList());

        regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REFERENCEID.toString(), refId,
                "DemoDedupe::performDedupe()::exit | Total duplicates found: " + finalList.size());

        return finalList;
    }
}
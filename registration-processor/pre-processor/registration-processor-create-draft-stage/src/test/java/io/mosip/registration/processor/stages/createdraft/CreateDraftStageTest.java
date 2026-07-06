package io.mosip.registration.processor.stages.createdraft;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;

import org.json.simple.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.mosip.kernel.biometrics.spi.CbeffUtil;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.manager.dto.IdResponseDTO;
import io.mosip.registration.processor.packet.manager.exception.IdrepoDraftException;
import io.mosip.registration.processor.packet.manager.exception.IdrepoDraftReprocessableException;
import io.mosip.registration.processor.packet.manager.idreposervice.IdrepoDraftService;
import io.mosip.registration.processor.packet.storage.utils.IdSchemaUtil;
import io.mosip.registration.processor.packet.storage.utils.PriorityBasedPacketManagerService;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.packet.storage.utils.Utility;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.stages.createdraft.stage.CreateDraftStage;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * Unit tests for {@link CreateDraftStage}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CreateDraftStageTest {

    private static final String REG_ID = "10001100770000320200720095022";
    private static final String EXISTING_UIN = "9876543210";

    @InjectMocks
    private CreateDraftStage createDraftStage;

    @Mock
    private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

    @Mock
    private IdrepoDraftService idrepoDraftService;

    @Mock
    private Utility utility;

    @Mock
    private AuditLogRequestBuilder auditLogRequestBuilder;

    @Mock
    private RegistrationExceptionMapperUtil registrationStatusMapperUtil;

    @Mock
    private PriorityBasedPacketManagerService packetManagerService;

    @Mock
    private IdSchemaUtil idSchemaUtil;

    @Mock
    private Utilities utilities;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CbeffUtil cbeffutil;

    private MessageDTO messageDTO;
    private InternalRegistrationStatusDto registrationStatusDto;

    @Before
    public void setUp() {
        messageDTO = new MessageDTO();
        messageDTO.setRid(REG_ID);
        messageDTO.setIteration(1);
        messageDTO.setWorkflowInstanceId("wf-001");

        registrationStatusDto = new InternalRegistrationStatusDto();
        registrationStatusDto.setRegistrationId(REG_ID);
        registrationStatusDto.setRegistrationType("NEW");
        registrationStatusDto.setStatusCode(RegistrationStatusCode.PROCESSING.toString());

        when(registrationStatusService.getRegistrationStatus(anyString(), any(), any(), any()))
                .thenReturn(registrationStatusDto);
        when(registrationStatusMapperUtil.getStatusCode(any())).thenReturn("ERROR");

        // Populate-draft path stubs: make the new code in process() succeed without
        // forcing every test to re-stub them. Tests that need different behaviour can
        // override these.
        ReflectionTestUtils.setField(createDraftStage, "idRepoUpdate", "mosip.id.update");
        ReflectionTestUtils.setField(createDraftStage, "idRepoApiVersion", "v1");
        ReflectionTestUtils.setField(createDraftStage, "convertIdschemaToDouble", true);
        ReflectionTestUtils.setField(createDraftStage, "trimWhitespaces", false);

        try {
            when(packetManagerService.getFieldByMappingJsonKey(anyString(), anyString(), any(), any()))
                    .thenReturn("0.1");
            when(packetManagerService.getFields(anyString(), any(), any(), any()))
                    .thenReturn(new HashMap<>());
            when(idSchemaUtil.getDefaultFields(any(Double.class))).thenReturn(new ArrayList<>());
            when(utilities.getRegistrationProcessorMappingJson(anyString())).thenReturn(new JSONObject());
            when(idrepoDraftService.idrepoUpdateDraft(anyString(), any(), any()))
                    .thenReturn(new IdResponseDTO());
        } catch (Exception ignored) {
            // Mockito stubs declare the same checked exceptions as the mocked methods;
            // ignore here since setup never actually invokes them.
        }
    }

    // -----------------------------------------------------------------------
    // NEW packet – happy path
    // -----------------------------------------------------------------------

    @Test
    public void testProcess_NewPacket_NoDraftExists_Success() throws Exception {
        messageDTO.setReg_type("NEW");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, null)).thenReturn(true);

        MessageDTO result = createDraftStage.process(messageDTO);

        assertTrue(result.getIsValid());
        assertFalse(result.getInternalError());
        verify(idrepoDraftService, never()).idrepoDiscardDraft(anyString());
        verify(idrepoDraftService, times(1)).idrepoCreateDraft(REG_ID, null);
    }

    @Test
    public void testProcess_NewPacket_DraftAlreadyExists_DiscardAndRecreate() throws Exception {
        messageDTO.setReg_type("NEW");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(true);
        when(idrepoDraftService.idrepoDiscardDraft(REG_ID)).thenReturn(true);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, null)).thenReturn(true);

        MessageDTO result = createDraftStage.process(messageDTO);

        assertTrue(result.getIsValid());
        assertFalse(result.getInternalError());
        verify(idrepoDraftService, times(1)).idrepoDiscardDraft(REG_ID);
        verify(idrepoDraftService, times(1)).idrepoCreateDraft(REG_ID, null);
    }

    // -----------------------------------------------------------------------
    // UPDATE packet – happy path
    // -----------------------------------------------------------------------

    @Test
    public void testProcess_UpdatePacket_NoDraftExists_Success() throws Exception {
        messageDTO.setReg_type("UPDATE");
        registrationStatusDto.setRegistrationType("UPDATE");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(utility.getUIn(anyString(), anyString(), any())).thenReturn(EXISTING_UIN);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, EXISTING_UIN)).thenReturn(true);

        MessageDTO result = createDraftStage.process(messageDTO);

        assertTrue(result.getIsValid());
        assertFalse(result.getInternalError());
        verify(idrepoDraftService, times(1)).idrepoCreateDraft(REG_ID, EXISTING_UIN);
    }

    // -----------------------------------------------------------------------
    // Non-NEW/UPDATE packet types – skip
    // -----------------------------------------------------------------------

    @Test
    public void testProcess_LostPacket_Skipped() throws Exception {
        messageDTO.setReg_type("LOST");
        registrationStatusDto.setRegistrationType("LOST");

        MessageDTO result = createDraftStage.process(messageDTO);

        assertTrue(result.getIsValid());
        assertFalse(result.getInternalError());
        verify(idrepoDraftService, never()).idrepoHasDraft(anyString());
        verify(idrepoDraftService, never()).idrepoCreateDraft(anyString(), anyString());
    }

    @Test
    public void testProcess_ActivatedPacket_Skipped() throws Exception {
        messageDTO.setReg_type("ACTIVATED");
        registrationStatusDto.setRegistrationType("ACTIVATED");

        MessageDTO result = createDraftStage.process(messageDTO);

        assertTrue(result.getIsValid());
        assertFalse(result.getInternalError());
        verify(idrepoDraftService, never()).idrepoCreateDraft(anyString(), anyString());
    }

    // -----------------------------------------------------------------------
    // Error scenarios
    // -----------------------------------------------------------------------

    @Test
    public void testProcess_NewPacket_DraftCreationReturnsFalse_InternalError() throws Exception {
        messageDTO.setReg_type("NEW");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, null)).thenReturn(false);

        MessageDTO result = createDraftStage.process(messageDTO);

        assertFalse(result.getIsValid());
        assertTrue(result.getInternalError());
        verify(idrepoDraftService, times(1)).idrepoCreateDraft(REG_ID, null);
    }

    @Test
    public void testProcess_NewPacket_CreateDraftThrowsApiException_InternalError() throws Exception {
        messageDTO.setReg_type("NEW");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, null))
                .thenThrow(new ApisResourceAccessException("ID Repo not reachable"));

        MessageDTO result = createDraftStage.process(messageDTO);

        assertFalse(result.getIsValid());
        assertTrue(result.getInternalError());
    }

    @Test
    public void testProcess_UpdatePacket_UinNotFound_InternalError() throws Exception {
        messageDTO.setReg_type("UPDATE");
        registrationStatusDto.setRegistrationType("UPDATE");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(utility.getUIn(anyString(), anyString(), any())).thenReturn(null);

        MessageDTO result = createDraftStage.process(messageDTO);

        assertFalse(result.getIsValid());
        assertTrue(result.getInternalError());
        verify(idrepoDraftService, never()).idrepoCreateDraft(anyString(), anyString());
    }

    @Test
    public void testProcess_DraftCreationFails_InternalError() throws Exception {
        messageDTO.setReg_type("NEW");

        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(false);
        when(idrepoDraftService.idrepoCreateDraft(REG_ID, null))
                .thenThrow(new IdrepoDraftException("CDS-001", "Draft creation failed"));

        MessageDTO result = createDraftStage.process(messageDTO);

        assertFalse(result.getIsValid());
        assertTrue(result.getInternalError());
    }

    @Test
    public void testProcess_DraftCreationReprocessableException_InternalError() throws Exception {
        messageDTO.setReg_type("NEW");

        // Draft already exists; discarding it raises a reprocessable exception
        when(idrepoDraftService.idrepoHasDraft(REG_ID)).thenReturn(true);
        when(idrepoDraftService.idrepoDiscardDraft(REG_ID))
                .thenThrow(new IdrepoDraftReprocessableException("IDR-IDS-003", "Key manager error"));

        MessageDTO result = createDraftStage.process(messageDTO);

        assertFalse(result.getIsValid());
        assertTrue(result.getInternalError());
    }

}

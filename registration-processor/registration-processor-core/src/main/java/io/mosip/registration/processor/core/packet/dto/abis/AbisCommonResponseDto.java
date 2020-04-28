package io.mosip.registration.processor.core.packet.dto.abis;

import java.io.Serializable;
import java.time.LocalDateTime;

<<<<<<< HEAD
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
=======
import org.apache.htrace.shaded.fasterxml.jackson.annotation.JsonFormat;
>>>>>>> 845c0c172f85442c1ac5c53a21309a9db56a146e

import lombok.Data;

@Data
public class AbisCommonResponseDto implements Serializable{
	
	private static final long serialVersionUID = -7080424253600088998L;
	
	private String id;

	private String requestId;
	
	private String responsetime;
	
	private Integer returnValue;
	
	private Integer failureReason;

}

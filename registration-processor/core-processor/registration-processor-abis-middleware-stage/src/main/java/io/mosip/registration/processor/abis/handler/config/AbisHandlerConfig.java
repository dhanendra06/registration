package io.mosip.registration.processor.abis.handler.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AbisHandlerConfig {

	@Value("${mosip.regproc.abis.request.executor.pool.size:10}")
	private int fetchPoolSize;

	private ExecutorService fetchPool;

	@Bean(name = "abisRequestExecutor")
	public ExecutorService abisRequestExecutor() {
		fetchPool = Executors.newFixedThreadPool(fetchPoolSize,
				Thread.ofPlatform().name("pkt-fetch-", 0).daemon(true).factory());
		return fetchPool;
	}
}

package io.mosip.registration.processor.stages.app;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import io.mosip.registration.processor.stages.demodedupe.DemoDedupeStage;

public class DemodedupeApplication {

	private static final String[] CONFIG_PACKAGES = { "io.mosip.registration.processor.core.config",
			"io.mosip.registration.processor.stages.config", "io.mosip.registration.processor.demo.dedupe.config",
			"io.mosip.registration.processor.status.config", "io.mosip.registration.processor.packet.storage.config",
			"io.mosip.registration.processor.core.kernel.beans",
			"io.mosip.registration.processor.packet.manager.config", "io.mosip.kernel.packetmanager.config" };

	/**
	 * The main method to bootstrap the application context and deploy Demo Dedupe
	 * Stage.
	 *
	 * @param args application arguments
	 */
	public static void main(String[] args) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.scan(CONFIG_PACKAGES);
		context.refresh();

		DemoDedupeStage demodedupeStage = context.getBean(DemoDedupeStage.class);
		demodedupeStage.deployVerticle();
	}
}
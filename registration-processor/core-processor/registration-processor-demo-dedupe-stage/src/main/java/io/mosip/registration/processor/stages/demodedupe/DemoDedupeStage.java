package io.mosip.registration.processor.stages.demodedupe;

import io.mosip.registration.processor.core.abstractverticle.*;
import io.vertx.core.Future;
import io.vertx.core.WorkerExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Class DemoDedupeStage.
 * Optimized version with async batching and fixed MosipEventBus usage.
 */
@RefreshScope
@Service
@Configuration
@ComponentScan(basePackages = {
        "${mosip.auth.adapter.impl.basepackage}",
        "io.mosip.registration.processor.core.config",
        "io.mosip.registration.processor.stages.config",
        "io.mosip.registration.processor.demo.dedupe.config",
        "io.mosip.registration.processor.status.config",
        "io.mosip.registration.processor.packet.storage.config",
        "io.mosip.registration.processor.core.kernel.beans",
        "io.mosip.registration.processor.packet.manager.config",
        "io.mosip.kernel.packetmanager.config"
})
public class DemoDedupeStage extends MosipVerticleAPIManager {

    private static final String STAGE_PROPERTY_PREFIX = "mosip.regproc.demo.dedupe.";

    @Value("${vertx.cluster.configuration}")
    private String clusterManagerUrl;

    @Value("${worker.pool.size:64}")
    private Integer workerPoolSize;

    @Value("${mosip.regproc.demo.dedupe.message.expiry-time-limit:60}")
    private Long messageExpiryTimeLimit;

    @Autowired
    private DemodedupeProcessor demodedupeProcessor;

    @Autowired
    private MosipRouter router;

    private MosipEventBus mosipEventBus;
    private WorkerExecutor workerExecutor;

    /** Queue for batching messages */
    private final ConcurrentLinkedQueue<MessageDTO> messageQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private static final int BATCH_SIZE = 200;  // Tune based on expected load

    /** Timer ID for periodic flushing */
    private long flushTimerId = -1;

    @PostConstruct
    public void init() {
        deployVerticle();
    }

    public void deployVerticle() {
        mosipEventBus = this.getEventBus(this, clusterManagerUrl, workerPoolSize);
        workerExecutor = getVertx().createSharedWorkerExecutor("demo-dedupe-worker-pool", workerPoolSize, 30000);

        // Consume messages asynchronously
        mosipEventBus.consume(MessageBusAddress.DEMO_DEDUPE_BUS_IN, (event, asyncHandler) -> {
            MessageDTO dto = event.getBody().mapTo(MessageDTO.class);
            if (dto != null) {
                messageQueue.add(dto);
                if (queueSize.incrementAndGet() >= BATCH_SIZE) {
                    flushBatch();
                }
            }
            asyncHandler.handle(Future.succeededFuture(null)); // Acknowledge receipt
        });

        // Scheduled batch flush every 100ms (in case queue doesn't fill completely)
        flushTimerId = getVertx().setPeriodic(messageExpiryTimeLimit, timer -> flushBatch());
    }

    private void flushBatch() {
        if (queueSize.get() == 0) return;

        List<MessageDTO> batch = new ArrayList<>(BATCH_SIZE);
        while (!messageQueue.isEmpty() && batch.size() < BATCH_SIZE) {
            MessageDTO msg = messageQueue.poll();
            if (msg != null) batch.add(msg);
        }
        queueSize.addAndGet(-batch.size());

        if (!batch.isEmpty()) {
            workerExecutor.executeBlocking(promise -> {
                List<MessageDTO> processed = new ArrayList<>(batch.size());
                for (MessageDTO msg : batch) {
                    processed.add(demodedupeProcessor.process(msg, getStageName()));
                }
                promise.complete(processed);
            }, false, result -> {
                if (result.succeeded()) {
                    @SuppressWarnings("unchecked")
                    List<MessageDTO> processedList = (List<MessageDTO>) result.result();
                    processedList.forEach(processed ->
                            mosipEventBus.send(MessageBusAddress.DEMO_DEDUPE_BUS_OUT, processed)
                    );
                }
            });
        }
    }

    @Override
    public void start() {
        router.setRoute(this.postUrl(getVertx(),
                MessageBusAddress.DEMO_DEDUPE_BUS_IN,
                MessageBusAddress.DEMO_DEDUPE_BUS_OUT));
        this.createServer(router.getRouter(), getPort());
    }

    @Override
    protected String getPropertyPrefix() {
        return STAGE_PROPERTY_PREFIX;
    }

    @Override
    public MessageDTO process(MessageDTO object) {
        return demodedupeProcessor.process(object, getStageName());
    }

    @PreDestroy
    public void shutdown() {
        if (flushTimerId != -1) {
            getVertx().cancelTimer(flushTimerId);
        }
        if (workerExecutor != null) {
            workerExecutor.close();
        }
    }
}
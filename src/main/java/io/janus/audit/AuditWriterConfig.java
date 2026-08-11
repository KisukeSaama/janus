package io.janus.audit;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor that writes gateway audit events off the request path.
 *
 * <p>Two properties make this safe to do asynchronously. Saturation runs the write on the calling
 * thread instead of discarding it, so a burst costs latency rather than an event; and shutdown waits
 * for the queue to drain, so a normal stop does not lose what is still pending. A kill -9 can still
 * lose the tail of the queue, which is the price of not paying for an insert on every proxied call.
 */
@Configuration
public class AuditWriterConfig {

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor auditWriterExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("janus-audit-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        // Bounded on purpose: an unbounded queue turns a database outage into heap exhaustion.
        executor.setQueueCapacity(2048);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

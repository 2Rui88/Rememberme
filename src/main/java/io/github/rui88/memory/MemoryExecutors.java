package io.github.rui88.memory;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class MemoryExecutors {
    @Bean
    Executor memoryLoadExecutor() {
        return pool("memory-load-", 4, 12, 64);
    }

    @Bean
    Executor memoryWriteExecutor() {
        return pool("memory-write-", 2, 6, 64);
    }

    private static ThreadPoolTaskExecutor pool(String prefix, int core, int max, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}

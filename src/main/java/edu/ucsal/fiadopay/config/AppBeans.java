package edu.ucsal.fiadopay.config;

import edu.ucsal.fiadopay.concurrent.WorkerPool;
import edu.ucsal.fiadopay.plugins.PluginRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppBeans {
    @Bean
    public PluginRegistry pluginRegistry() {
        return new PluginRegistry("edu.ucsal.fiadopay.plugins");
    }

    @Bean
    public WorkerPool workerPool() {
        return new WorkerPool();
    }
}

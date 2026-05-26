package com.resumeshaper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import com.resumeshaper.config.AppProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class ResumeShaperApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeShaperApplication.class, args);
    }
}

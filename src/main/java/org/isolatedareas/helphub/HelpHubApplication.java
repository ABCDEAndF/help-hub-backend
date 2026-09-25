package org.isolatedareas.helphub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class HelpHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(HelpHubApplication.class, args);
    }
}


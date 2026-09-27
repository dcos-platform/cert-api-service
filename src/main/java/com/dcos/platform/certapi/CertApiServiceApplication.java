package com.dcos.platform.certapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CertApiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CertApiServiceApplication.class, args);
    }
}

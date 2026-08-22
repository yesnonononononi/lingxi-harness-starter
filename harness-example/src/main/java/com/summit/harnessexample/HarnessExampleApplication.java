package com.summit.harnessexample;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.stereotype.Component;

@ComponentScan(basePackages = "com.summit")
@SpringBootApplication
public class HarnessExampleApplication {


    public static void main(String[] args) {
        SpringApplication.run(HarnessExampleApplication.class, args);
    }

}

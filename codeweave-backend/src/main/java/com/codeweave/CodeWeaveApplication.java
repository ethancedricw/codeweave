package com.codeweave;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeWeaveApplication { public static void main(String[] args) { SpringApplication.run(CodeWeaveApplication.class, args); } }

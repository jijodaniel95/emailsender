package com.email.emailsender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.retry.annotation.EnableRetry;
import java.util.concurrent.CountDownLatch;

@SpringBootApplication
@IntegrationComponentScan
@EnableRetry
public class EmailsenderApplication {

	private static CountDownLatch latch = new CountDownLatch(1);

	public static void main(String[] args) throws InterruptedException {
		ConfigurableApplicationContext ctx = SpringApplication.run(EmailsenderApplication.class, args);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			ctx.close();
			latch.countDown();
		}));

		latch.await(); // Keep the app running until explicitly shutdown
	}
}

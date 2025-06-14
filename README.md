# Email Sender Application

This Spring Boot application consumes messages from Google Pub/Sub and sends HTML emails using Gmail SMTP. It's designed for processing notifications, such as contact form submissions.


## Prerequisites
- Java 17 or higher
- Maven for dependency management
- Google Cloud SDK installed and configured
- A Google Cloud project with Pub/Sub enabled (e.g., project ID: aerial-bonfire-462121-i9)
- A Pub/Sub topic and subscription (e.g., 'contact-me-sub')
- Gmail account with App Password for SMTP (due to 2FA requirements)
- Environment variables set for sensitive data like email credentials

## Getting Started
1. Clone the repository: `git clone https://github.com/jijodaniel95/emailsender.git`
2. Navigate to the project directory: `cd emailsender`
3. Set up environment variables (e.g., in your shell or .env file):
   - `export SPRING_MAIL_USERNAME=your_email@gmail.com`
   - `export SPRING_MAIL_PASSWORD=your_app_password`
   - `export APP_MAIL_OWNER=your_email@gmail.com`
4. Configure Google Cloud credentials (e.g., place your service account key at the specified location in application.properties).
5. Build and run the application using Maven: `mvn clean install && mvn spring-boot:run`

## Configuration
- Edit `src/main/resources/application.properties` for:
  - GCP settings: `spring.cloud.gcp.project-id` and `spring.cloud.gcp.credentials.location`
  - Pub/Sub: `pubsub.subscription.name`
  - Email: `spring.mail.host`, `spring.mail.port`, `spring.mail.username`, `spring.mail.password`, and ensure they use environment variables for security (e.g., `${SPRING_MAIL_USERNAME}`)
  - Example:
    ```properties
    spring.mail.username=${SPRING_MAIL_USERNAME}
    spring.mail.password=${SPRING_MAIL_PASSWORD}
    app.mail.sender=${SPRING_MAIL_USERNAME}
    app.mail.owner=${APP_MAIL_OWNER}
    ```

## Running the Application
- Start the app: `mvn spring-boot:run`
- It will listen for Pub/Sub messages and send emails accordingly. Ensure your Pub/Sub subscription is active and permissions are set (e.g., service account with Pub/Sub Subscriber role).

## Troubleshooting
- **Pub/Sub Errors:** If messages aren't processing, check subscription existence in Google Cloud Console and ensure auto-create is enabled in properties.
- **Email Authentication Failures:** Verify App Password and ensure the 'From' address matches your SMTP username. Emails going to spam may require SPF/DKIM setup if using a custom domain.
- **Dependency Issues:** Ensure compatible versions of Spring Boot and Spring Cloud GCP. Run `mvn dependency:resolve` if needed.
- **Application Shuts Down Immediately:** If the app starts and stops, confirm the main class is blocking (e.g., via CountDownLatch).
- **General:** Check logs for errors and enable DEBUG logging for Spring and GCP components.

## Contributing
- Fork the repository.
- Make changes and submit a pull request.

## License
This project is under the MIT License (or specify your license here).

For more details, refer to the project documentation. 
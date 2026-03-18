# ToDo and Next Steps

This repository is a production-intended PC heartbeat dashboard with backend (Spring Boot) and frontend (JavaFX).

Important Notes
- Do not run builds until Gradle wrapper is stabilized for your environment. Use Java 17+ and Gradle 9.x for Java 21.
- Database: SQLite is used for convenience. For production use Postgres or MySQL and add Flyway/Liquibase migrations.
- Authentication: Passwords are hashed using BCrypt. Replace with JWT for stateless APIs and implement refresh tokens.

Priority Tasks
1. Ensure multi-module Gradle config is consistent across root and subprojects.
   - Run: `./gradlew wrapper --gradle-version 9.4.0` on a machine with internet access if wrapper is missing.
   - Then run: `./gradlew clean build --no-daemon --stacktrace` to validate build.

2. Migration and DB
   - Convert `schema.sql` into versioned Flyway migrations (e.g., `V1__init.sql`) and add Flyway plugin.
   - Backup existing DB before migrating production.

3. Authentication
   - Implement JWT token issuance on /api/v1/auth/login and require Authorization: Bearer <token> for protected endpoints.
   - Secure endpoints for admin operations (group and module management) with ROLE_ADMIN.

4. Frontend
   - Implement login screen to obtain JWT and store it in memory; attach Authorization header to API requests.
   - Improve dashboard module UI: resizing, editing, context menu (delete), and z-ordering.
   - Implement mobile/responsive fallback or web-based frontend for lightweight deployments.

5. Reporting and PDF
   - Validate OpenHTMLToPDF rendering across environments; consider wkhtmltopdf if advanced rendering required.
   - Add report scheduling and emailed report attachments (MailService exists).

6. CI/CD
   - Add GitHub Actions to run `./gradlew build` on PRs and run unit/integration tests.
   - Add containerization: Dockerfile for backend with Java 21 and multi-stage build; Docker Compose for DB.

7. Observability
   - Add metrics (Micrometer) and health endpoints. Connect to Prometheus/Grafana.

8. Tests
   - Add unit tests for ReportService, GroupController, DashboardModuleController, and UI tests for frontend.

9. Documentation
   - This file should be kept updated with steps taken and next tasks. Update changelog for major changes.

Contact
- Maintainer: GitHub Copilot (automated coding assistant)

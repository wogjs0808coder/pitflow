# PitFlow contributor instructions

- This repository is a staged graduation project: Next.js frontend, Java 17 / Spring Boot backend, PostgreSQL.
- Read README.md, docs/ROADMAP.md, and docs/ARCHITECTURE.md before changes. Keep implementation status accurate.
- Preserve server-side ownership checks and ADMIN authorization. Never accept ownerId or role assignment from customer input.
- Keep session authentication and CSRF protection. Never put passwords or tokens in browser storage or committed files.
- Use new Flyway migrations after a migration has been applied. Do not add Hibernate automatic schema updates.
- Do not show sample prices as verified real-world quotes.
- Add targeted tests for authentication, authorization, transaction and concurrency behavior. Avoid tests that only mirror trivial code.
- Before delivery run backend tests and frontend build/typecheck. Report unavailable PostgreSQL, Docker, browser or CI checks accurately.
- Supply Windows PowerShell commands when explaining local execution.
- Keep source in this repository. Do not alter the unrelated traffic-risk repositories.

# Tableteller Tools

MCP server: solo-RPG oracle + campaign journal for AI DMs.
Full spec: docs/SPEC.md. Read the relevant section before changing anything.

## Stack
Java 21, Spring Boot 4.1, Spring AI 2.0 MCP server (stdio), Flyway, PostgreSQL 16, Testcontainers.

## Commands
- Build/test: ./gradlew test
- Run: ./gradlew bootRun
- DB: docker compose up -d

## Rules
- All random numbers go through RandomSource; tests use a scripted or seeded one.
- No table text from published games in the repo. Real packs live in ~/.oracle/packs/.
- Every oracle tool writes a roll_log row.
- Work one milestone at a time; don't start the next until tests pass.

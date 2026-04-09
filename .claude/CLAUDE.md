# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4.0 application for practicing transaction isolation levels and propagation behavior across PostgreSQL and MySQL. Java 21, Gradle 9.4.

## Build & Run

```bash
# Build
./gradlew build

# Run with PostgreSQL (default)
./gradlew bootRun

# Run with MySQL
SPRING_PROFILE=mysql ./gradlew bootRun

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "eastmeet.voyage.transaction.VoyageTransactionApplicationTests"
```

## Infrastructure

```bash
# Start both databases
docker compose up -d

# Start one database
docker compose up -d postgresql
docker compose up -d mysql
```

## Multi-DB Profile Setup

- `application.yml` — shared config (JPA, logging)
- `application-postgresql.yml` — PostgreSQL datasource (`POSTGRE_*` env vars)
- `application-mysql.yml` — MySQL datasource (`MYSQL_*` env vars)
- `.env` — environment variables consumed by both docker-compose and Spring Boot

Profile is controlled via `SPRING_PROFILE` env var (defaults to `postgresql`).

## Key Configuration

- **Transaction logging enabled**: `org.springframework.transaction: DEBUG` — shows transaction creation, participation, commit/rollback
- **SQL logging enabled**: `hibernate.show_sql: true`, `format_sql: true`, plus p6spy for bind parameters
- **OSIV disabled**: `open-in-view: false` — transaction boundaries are explicit

## Code Convention

- null/String 검증 시 Apache Commons Lang3(`Validate.notNull`, `Validate.notBlank`, `Validate.isTrue`) 사용

## Package Structure

Base package: `eastmeet.voyage.transaction`

## Git Rules

- PR merge는 사용자가 직접 수행. Claude는 PR 생성까지만 허용, merge 금지.
- 브랜치 병합 시 merge 대신 rebase 사용. (`git rebase origin/main` → `git push --force-with-lease`)

## PR Message Convention

```
PR Title
[Type]: <간결한 제목>

Summary
<변경 목적을 1~2문장으로 설명>

Changes
<도메인/모듈 단위로 구분하여 변경사항 나열>

Technical Notes
<기술적 맥락, 설정, 의존성 등 참고사항>

Checklist
<이번 PR에서 완료한 항목과 다음 PR로 넘긴 항목>
```

- type: `[Feature]`, `[Fix]`, `[Refactor]`, `[Chore]`, `[Docs]`, `[Test]`
- Changes는 도메인/모듈별로 그룹핑
- Checklist에서 다음 PR 항목은 `(다음 PR)`로 표기
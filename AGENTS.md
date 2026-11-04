# Project Instructions

## Project
A pet adoption and temporary foster-care platform for users in China.

## Tech Stack
- Backend: Java 17+, Spring Boot
- Database: PostgreSQL + PostGIS
- Cache: Redis
- Frontend: Vue 3 + TypeScript + uni-app
- Deployment: Docker + Nginx

## Architecture
- Use a modular monolith initially.
- Keep controllers, services, repositories, DTOs and entities separated.
- Business rules must be implemented in the backend.
- Frontend clients must not independently implement business state transitions.
- Keep map provider integration behind an adapter.

## Development Rules
- Read relevant docs before using unfamiliar APIs.
- Follow TDD for important business logic.
- Write tests for bug fixes.
- Do not introduce new dependencies without explaining why.
- Do not modify unrelated modules.
- Run relevant tests after changes.
- Update API and architecture documentation when behavior changes.

## Security
- Never expose precise home addresses publicly.
- Enforce ownership and authorization on the backend.
- Validate all state transitions server-side.
- Never commit secrets, API keys, tokens or passwords.
- Use test data and isolated databases for automated agents.

## Delivery
Before finishing a task, report:
1. Files changed
2. Implementation details
3. Tests executed and results
4. Remaining risks
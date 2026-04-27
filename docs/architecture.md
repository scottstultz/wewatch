# Backend Package Conventions

The backend uses a feature-light layered package structure under `com.wewatch.api` so the codebase can grow without collapsing unrelated concerns into a single package.

Packages:

- `controller`: HTTP controllers and endpoint definitions.
- `service`: application services that contain business workflows and orchestration.
- `repository`: persistence access interfaces and data access components.
- `model`: domain models and JPA entities when persistence is added.
- `dto`: request and response objects used at API boundaries.
- `exception`: custom exceptions and centralized API error handling.

Conventions:

- Controllers should stay thin and delegate business work to services.
- Services should own application logic and coordinate repositories.
- Repositories should focus on persistence concerns only.
- DTOs should represent API contracts and should not double as persistence models.
- Models should represent domain or persistence state, not HTTP transport concerns.
- Exception handling should be centralized in the `exception` package as the API surface grows.

Current endpoint-specific code such as the health check may stay in a small feature package until shared conventions justify moving it into the broader `controller` and `dto` structure.

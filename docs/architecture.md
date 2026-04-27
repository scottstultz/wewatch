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

# Local Database Strategy

The MVP local database choice is PostgreSQL running in Docker.

Why PostgreSQL:

- It supports the MVP domain model cleanly without adding operational complexity beyond a single container.
- It matches the project’s planned stack and avoids a later migration from an embedded development database.
- It handles relational constraints well for `User`, `Title`, `WatchlistEntry`, `Review`, `CustomList`, and `CustomListItem`.

Local development approach:

- Run PostgreSQL in Docker with a named volume for persistent local data.
- Use a Spring `local` profile for datasource settings.
- Keep database credentials and host values overridable through environment variables.

Default local connection values:

- host: `localhost`
- port: `5432`
- database: `wewatch`
- username: `wewatch`
- password: `wewatch`

# Initial Domain Model

The initial domain model is MVP-focused. It supports content discovery, status-based tracking, simple reviews, and user-managed custom lists without designing for social features, provider syncing, or recommendation systems yet.

## Primary Objects

### User

Represents a single application user who owns tracked titles, reviews, and custom lists.

Initial fields:

- `id`: internal unique identifier
- `email`: unique login or contact identifier
- `displayName`: user-facing name
- `createdAt`: account creation timestamp
- `updatedAt`: last account update timestamp

Notes:

- MVP assumes a single-user or basic account model, but the data shape should still support multiple users cleanly.

### Title

Represents a movie or TV show known to the system.

Initial fields:

- `id`: internal unique identifier
- `externalId`: identifier from the upstream content provider such as TMDB
- `externalSource`: provider name, such as `TMDB`
- `type`: `MOVIE` or `TV`
- `name`: primary title name
- `overview`: short summary text
- `releaseDate`: release date or first air date
- `posterUrl`: poster image URL
- `createdAt`: first time this title was stored locally
- `updatedAt`: last metadata update timestamp

Notes:

- Title metadata should be shared across users rather than duplicated per user.
- MVP keeps this intentionally small and avoids deep provider-specific metadata.

### WatchlistEntry

Represents a user tracking relationship to a title.

Initial fields:

- `id`: internal unique identifier
- `userId`: owning user
- `titleId`: tracked title
- `status`: `WANT_TO_WATCH`, `WATCHING`, or `WATCHED`
- `addedAt`: when the title was first tracked
- `updatedAt`: last status or metadata update timestamp
- `startedAt`: optional timestamp for when watching began
- `completedAt`: optional timestamp for when watching was completed

Notes:

- This is the core MVP object for personal tracking.
- There should be at most one `WatchlistEntry` per user and title.

### Review

Represents a user-authored review or note for a title.

Initial fields:

- `id`: internal unique identifier
- `userId`: review author
- `titleId`: reviewed title
- `watchlistEntryId`: optional link to the user’s tracked item
- `rating`: optional numeric rating
- `reviewText`: optional written review or note
- `createdAt`: review creation timestamp
- `updatedAt`: last review update timestamp

Notes:

- Reviews are user-owned and should not be modeled as shared public content in the MVP.
- Rating and text are both optional so the model can support quick ratings, notes, or fuller reviews later.

### CustomList

Represents a user-curated list of titles outside the default watch-status buckets.

Initial fields:

- `id`: internal unique identifier
- `userId`: list owner
- `name`: list name
- `description`: optional short description
- `isPrivate`: visibility flag, defaulting to private in the MVP
- `createdAt`: list creation timestamp
- `updatedAt`: last list update timestamp

Notes:

- A custom list will also require list-item records in implementation, even if that support object is not called out as a primary model here.
- MVP assumes custom lists are personal, not collaborative.

## Supporting Relationship Object

### CustomListItem

This is not one of the requested primary models, but it is required to represent the many-to-many relationship between `CustomList` and `Title`.

Initial fields:

- `id`: internal unique identifier
- `customListId`: owning list
- `titleId`: included title
- `position`: optional manual ordering value
- `addedAt`: when the title was added to the list

## Relationships

- A `User` has many `WatchlistEntry` records.
- A `User` has many `Review` records.
- A `User` has many `CustomList` records.
- A `Title` can appear in many `WatchlistEntry` records across users.
- A `Title` can have many `Review` records across users.
- A `Title` can appear in many `CustomListItem` records.
- A `WatchlistEntry` belongs to one `User` and one `Title`.
- A `Review` belongs to one `User` and one `Title`, and may optionally reference one `WatchlistEntry`.
- A `CustomList` belongs to one `User`.
- A `CustomList` has many `CustomListItem` records.
- A `CustomListItem` belongs to one `CustomList` and one `Title`.

## Suggested Constraints

- `User.email` should be unique.
- `Title.externalSource` plus `Title.externalId` should be unique.
- `WatchlistEntry.userId` plus `WatchlistEntry.titleId` should be unique.
- `CustomListItem.customListId` plus `CustomListItem.titleId` should be unique.

## Assumptions

- The application will use an external title provider, so `Title` is treated as imported reference data plus a small local cache of metadata.
- Tracking status is modeled on `WatchlistEntry`, not on `Title`, because status is user-specific.
- Reviews are scoped per user and title and are not treated as social content in the MVP.
- Custom lists are optional organization tools in addition to the default watch-status flow.
- Episode-level tracking is out of scope for this initial model.

## Open Questions

- Should `Review` allow multiple entries per user and title, or should it be limited to one editable review per user and title?
- Should ratings be integers only, decimals, or a smaller bounded scale such as 1 to 5?
- Should `CustomList` support ordered items from the start, or can ordering wait until list UI exists?
- Should `Title.releaseDate` remain a single field for both movies and TV, or should movies and TV eventually split into release-specific fields?

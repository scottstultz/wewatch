# WeWatch

WeWatch is a personal project for discovering, tracking, and organizing movies and TV shows.

The goal is to build a clean, modern app that helps users answer questions like:

- What should we watch tonight?
- What have we already seen?
- What’s on our watchlist?
- What streaming service is it on?
- What’s worth watching based on our tastes?

This project is also being built as a portfolio-quality full-stack application, with an emphasis on clean architecture, maintainability, and iterative delivery.

---

## Goals

WeWatch is intended to explore and demonstrate:

- Full-stack application development
- Modern frontend and backend architecture
- API integration with movie / TV data providers
- Authentication and user-specific watchlists
- Search, filtering, and recommendation workflows
- Clean project structure and scalable engineering practices

---

## MVP Scope

### Summary
The goal of the MVP is to deliver a focused, personal tracking experience that allows users to manage what they want to watch, are currently watching, and have already watched.

This MVP intentionally avoids feature creep and prioritizes a simple, fast, and usable core experience.

---

### Core User Actions

#### 1. Discover Content
- Search for movies and TV shows (via external API, e.g., TMDB)
- View basic content details:
  - Title
  - Poster
  - Overview
  - Release year
  - Content type (movie or TV show)

---

#### 2. Track Content Status
Users can assign and manage a status for each piece of content:

- `WANT_TO_WATCH`
- `WATCHING`
- `WATCHED`

Capabilities:
- Add content to a status
- Move content between statuses
- Remove content from tracking

---

#### 3. View Personal Library
- View all tracked content grouped by status:
  - Want to Watch
  - Watching
  - Watched
- Quickly scan and manage items within each group

---

### Out of Scope (MVP)

To maintain focus and speed of development, the following features are explicitly excluded from the MVP:

#### Social Features
- Friends / followers
- Shared lists
- Activity feeds

#### Advanced Tracking
- Episode-level tracking
- Watch progress (timestamps, percentages)
- Watch history timeline

#### Personalization
- Recommendations
- AI-driven suggestions

#### Content Interaction
- Ratings
- Reviews

#### UX Enhancements
- Notifications
- Advanced filtering and sorting

---

### Stretch Features (Post-MVP)

The following features may be considered after the MVP is complete:

- Ratings (e.g., 1–5 stars or thumbs up/down)
- Reviews and notes
- Episode tracking for TV shows
- Social features (friends, shared watchlists)
- Recommendation system (rule-based or AI-powered)

---

### Roadmap

#### Phase 1 — MVP
- Content search and detail view
- Status-based tracking system
- Personal library view
- Basic persistence (user + tracked content)

#### Phase 2 — Enrichment
- Ratings
- Reviews
- Filtering and sorting

#### Phase 3 — Engagement
- Social features
- Recommendations
- Enhanced discovery

---

### Acceptance Criteria

- MVP scope is clearly defined and documented
- Scope is constrained to core tracking functionality
- Out-of-scope features are explicitly listed
- Roadmap is broken into clear phases
- `docs/roadmap.md` reflects this MVP definition

---

## Planned Features

Initial and future feature ideas include:

- Search for movies and TV shows
- View title details (poster, overview, release year, genres, ratings)
- Add/remove titles from a personal watchlist
- Mark titles as watched
- Filter by genre, streaming availability, rating, etc.
- Personalized recommendations
- User accounts and saved preferences
- Responsive UI for desktop and mobile use

This feature set will evolve over time as the project grows.

---

## Tech Stack (Planned)

### Frontend
- React
- TypeScript
- Vite
- Tailwind CSS

### Backend
- Java
- Spring Boot
- REST API

### Data / Infra
- PostgreSQL
- Docker
- GitHub Actions

> Final stack decisions may evolve as the project develops.

---

## Repository Structure

```text
wewatch/
├── backend/        # Spring Boot API
├── frontend/       # React frontend
├── docs/           # Architecture notes, planning, ADRs, etc.
├── .github/        # GitHub workflows, issue templates, PR templates
└── README.md
```

## Getting Started

### Prerequisites

To work on WeWatch locally, you’ll eventually want the following installed:

- [Git](https://git-scm.com/)
- [Node.js](https://nodejs.org/)
- Java 21+ (or whichever version the backend targets)
- [Docker](https://www.docker.com/)

### Clone the Repository

git clone git@github.com:<your-username>/wewatch.git
cd wewatch

## Development Approach

This project is being built incrementally using an issue-based workflow with small, reviewable changes.

### Principles

- Start with a small, usable MVP
- Build in vertical slices where possible
- Favor clarity and maintainability over premature complexity
- Treat the project like a real production app
- Use documentation and clean workflow habits from the beginning

### Branch Naming Examples

feat/12-add-search-page
feat/18-create-title-details-endpoint
docs/2-initial-readme
docs/7-add-architecture-notes
fix/31-handle-empty-watchlist
chore/5-setup-github-actions

### Commit Style Examples
docs: add initial project README
feat: scaffold Spring Boot backend
feat: scaffold React frontend
chore: add gitignore and editor config
fix: handle empty watchlist state

### Pull Request Guidelines

PRs should aim to:

- stay small and focused
- map clearly to a GitHub issue
- include a concise summary of changes
- be easy to review and reason about

Suggested PR template:

    ## Summary
    Brief description of what this PR does.

    ## Changes
    - Added ...
    - Updated ...
    - Refactored ...

    ## Notes
    Optional implementation notes, tradeoffs, or follow-up considerations.

    Closes #<issue-number>

Example:

    ## Summary
    Adds the initial README for the WeWatch project.

    ## Changes
    - Added project overview
    - Added MVP scope
    - Added planned tech stack
    - Added repo structure and setup notes

    Closes #2

## Roadmap

### Near-Term Priorities
- [x] Establish repo structure
- [x] Create initial README
- [ ] Create GitHub issue / PR templates
- [ ] Scaffold frontend app
- [ ] Scaffold backend service
- [ ] Define external API integration approach
- [ ] Establish local development workflow

### MVP Milestones
- [ ] Search for titles
- [ ] View title details
- [ ] Save titles to a watchlist
- [ ] Mark titles as watched
- [ ] View and manage saved titles

### Future Enhancements
- [ ] User authentication
- [ ] Personalized recommendations
- [ ] Streaming provider integration
- [ ] Advanced filtering and sorting
- [ ] Production deployment
- [ ] CI/CD pipeline hardening

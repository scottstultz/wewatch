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

The initial MVP (Minimum Viable Product) for WeWatch is focused on helping a user track and manage what they want to watch.

### In Scope
- Search for movies and TV shows
- View basic title details
- Add titles to a watchlist
- Mark titles as watched
- View saved watchlist / watched items

### Out of Scope (for MVP)
- Advanced recommendation engine
- Streaming provider integrations
- Social features / shared watchlists
- AI-powered suggestions
- Notifications
- Production deployment polish

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

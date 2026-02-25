# How to Use Statistics for Better Learning

Mnema statistics are not just dashboards. They are a feedback loop that helps you study with less overload and better retention.

## Contents

- [Why card statistics matter](#why-card-statistics-matter)
- [What statistics are available in Mnema](#what-statistics-are-available-in-mnema)
- [How to use these metrics in daily practice](#how-to-use-these-metrics-in-daily-practice)
- [How Mnema collects and computes statistics](#how-mnema-collects-and-computes-statistics)
- [Backend and frontend overview](#backend-and-frontend-overview)

## Why Card Statistics Matter

Spaced repetition works best when you can see three things:

1. **Retention quality**: are you forgetting too often?
2. **Workload pressure**: are upcoming reviews manageable?
3. **Study speed**: are sessions efficient or too slow/fatiguing?

Without statistics, learners often react too late: backlog grows, `Again` rate spikes, and motivation drops.

With statistics, you can tune your routine before burnout:

- reduce new cards when due forecast grows,
- improve card quality when `Again` is high,
- pick better study times using hourly load.

## What Statistics Are Available in Mnema

Mnema provides account-level and deck-level analytics:

### Core KPI cards

- **Reviews**: total reviews for the selected period.
- **Success rate**: percentage of non-`Again` answers.
- **Avg/Median response time**: speed and consistency of recall.
- **Queue now / Due today**: immediate workload snapshot.

### Charts and breakdowns

- **Daily trend**: daily volume (or study time in seconds).
- **Hourly load**: what hours you study most.
- **Due forecast**: expected due cards in coming days.
- **Answer buttons**: distribution of `Again`, `Hard`, `Good`, `Easy`.
- **Sources**: where reviews came from (`web`, `mobile`, etc.).

### Filters

- Date range (`7d`, `30d`, `90d`, or custom).
- Forecast horizon (`14d`, `30d`, `60d`).
- Scope: whole account or one deck.
- Time zone aware grouping.

## How to Use These Metrics in Daily Practice

### 1. Start with Success + Again

- If `Again` is consistently high, first improve card clarity (shorter prompts, one fact per card).
- If success is high but response is too slow, simplify cards or split overloaded cards.

### 2. Use Due Forecast to avoid backlog

- If forecast spikes in coming days, temporarily reduce new cards.
- If forecast is flat and stable, you can safely increase new material.

### 3. Use Hourly Load to schedule sessions

- Pick 1-2 hours where your performance is best and keep sessions there.
- Consistency at a stable time is usually better than random long sessions.

### 4. Compare deck-level vs account-level

- If one deck has much higher `Again` than account average, that deck needs cleanup.
- If account-level load is high but deck-level is normal, you likely have too many active decks at once.

### 5. Iterate weekly

- Weekly check: retention trend, response speed, upcoming due load.
- Apply one small change per week (new card cap, card rewrite, session timing).
- Re-check next week and keep only changes that improved metrics.

## How Mnema Collects and Computes Statistics

Every answer in review writes a log entry. Each entry includes:

- card reference,
- review timestamp,
- rating (`Again/Hard/Good/Easy`),
- response time (`response_ms`),
- source (`web/mobile/api`),
- algorithm state snapshots before/after review.

From these logs and current card states Mnema computes:

- period aggregates (reviews, rates, response time),
- daily and hourly series,
- rating/source distributions,
- queue snapshot (`due now`, `due today`, `overdue`),
- due forecast using current `next_review_at`.

All statistics are scoped to the authenticated user. Deck analytics are additionally checked against deck ownership.

## Backend and Frontend Overview

### Backend (`core` service)

- API endpoint: `GET /review/stats`
- Filter params: `userDeckId`, `from`, `to`, `timeZone`, `dayCutoffMinutes`, `forecastDays`
- Main layers:
  - `ReviewStatsController`
  - `ReviewStatsService`
  - `ReviewStatsRepository` (native SQL aggregations)

Backend returns normalized datasets (including missing days/hours as zeros) so charts stay stable and predictable.

### Frontend (Angular)

- Shared analytics component: `review-stats-panel.component`
- Used in:
  - Profile page (account analytics)
  - Deck profile page (deck analytics)
- Features:
  - interactive filters,
  - hover details,
  - drag-to-scroll on dense charts,
  - responsive layout for desktop and mobile.

The goal of this design is practical interpretation, not vanity metrics: fewer surprises, fewer backlogs, better learning consistency.

# Project Instructions

## Scope

- This repository contains a Spring Boot backend in `tengen/` and a Next.js frontend in `frontend/`.
- Keep changes scoped to the relevant layer unless a cross-cutting fix is required.
- Prefer small, targeted edits over broad refactors.

## Working Style

- Read the existing code and project docs before changing behavior.
- Preserve the current architecture unless the user asks for a redesign.
- Do not rename or move files unless it clearly improves the task.
- Use ASCII by default unless a file already uses other characters.

## Frontend

- Treat `frontend/` as the Next.js app.
- Follow the existing React, MUI, and TanStack Query patterns already in the codebase.
- Keep UI changes consistent with the current design system unless the task is explicitly a redesign.

## Backend

- Treat `tengen/` as the Spring Boot service.
- Prefer backend changes that keep API contracts stable unless the user requests otherwise.
- Be careful with auth, API keys, and rule-processing logic because they affect runtime behavior.

## Verification

- For non-trivial changes, run the most relevant checks available for the touched area.
- Prefer focused verification first, then broader checks if needed.
- If you cannot run a check, explain what was not run and why.

## Communication

- Call out assumptions when the codebase does not make the answer obvious.
- If there are tradeoffs or behavioral risks, surface them clearly before making the change.

## Rules
- Be succint on output answers.
- When creating markdown plan files, add the date time today then the name for the naming of file.
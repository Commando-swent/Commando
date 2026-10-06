# AGENTS.md

This file defines the guidelines that AI coding agents must follow when working on the Command'o project.

## Project Architecture

- Follow the MVVM architecture used in the SwEnt bootcamp.
- Keep UI, ViewModels, models, and repositories separated.
- UI components must not directly access Firebase or other data sources.
- Data access should go through repositories.
- Keep changes small and focused on the requested task.
- Follow the existing project structure and conventions.
- Reuse existing components and patterns when possible.
- Avoid unnecessary dependencies or architectural changes.

## Code Quality

### Kotlin and Compose Conventions

- Follow the bootcamp's MVVM separation and the existing Command'o naming style.
- Use PascalCase for classes, enums, and composables; camelCase for functions and variables;
  uppercase snake case for constants.
- Declare navigation destinations in `ui/navigation/CommandoScreens.kt`, with a `@StringRes`
  title. Use the enum's `.name` for `NavHost`, `composable`, and `navigate` routes instead of
  repeating string literals. Keep only implemented destinations in the enum.
- Keep navigation in `CommandoApp`, called from `MainActivity`. Screens receive named action
  callbacks rather than owning the application's navigation controller.
- Keep screen state and ViewModels in the relevant `ui/` feature folder. Data models and
  repository interfaces belong in the data/model layer.
- Expose read-only `StateFlow` from ViewModels and keep `MutableStateFlow` private.
- Group UI test tags in an object with uppercase constant names, as in the bootcamp.
- Preserve session routing and clear authenticated navigation state on sign-out or account change.
- Reuse existing Command'o interfaces; do not copy bootcamp implementations or grading files.

- Write clear, readable, and maintainable Kotlin code.
- Follow the existing coding style of the project.
- Do not modify unrelated code.
- Avoid unnecessary complexity.
- Format code before committing.
- Add or update tests when implementing new behavior.
- Never include credentials, API secrets, or other sensitive information in the source code.

## Testing

Before considering a task complete, run:

```bash
./gradlew check
```

Run relevant unit tests and instrumentation tests when necessary.

Do not claim that a test or build passes unless it has actually been run successfully.

If a test cannot be run, clearly report it instead of assuming that it passes.

## Git Workflow

- Never push directly to `main`.
- Work on a dedicated branch.
- Changes must be merged through a Pull Request.
- Keep commits focused on one logical change.
- Use short and descriptive commit messages.
- Do not commit generated files, local configuration files, credentials, or secrets.
- Do not make unrelated changes in the same commit.

Examples of commit messages:

- `Add login screen`
- `Implement trip repository`
- `Add request creation flow`
- `Fix request validation`
- `Add Firebase authentication`

## AI Usage

AI-generated code must always be reviewed and understood by a team member before being merged.

When AI significantly contributes to a commit or Pull Request, acknowledge the use of AI according to the course requirements.

The human developer remains responsible for:

- Understanding the generated code.
- Reviewing the changes.
- Testing the implementation.
- Approving the final solution.

AI agents should assist development but should not make final project decisions without human review.

## Before Finishing a Task

Before declaring a task complete:

1. Check that the project builds.
2. Run `./gradlew check`.
3. Run the relevant tests.
4. Check that no unrelated files were modified.
5. Check that no credentials or secrets were introduced.
6. Summarize the changes that were made.
7. Report any remaining problems or tests that could not be run.

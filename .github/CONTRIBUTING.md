# Contributing to Escalation Loot

Thank you for your interest in contributing! This document outlines how to contribute to this project.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/Division-2-Proto-Loot-Tracker.git`
3. Create a new branch: `git checkout -b feature/your-feature-name`
4. Make your changes
5. Test your changes thoroughly
6. Commit with a clear message
7. Push to your fork
8. Open a Pull Request

## Development Setup

### Requirements
- **JDK 17** or newer
- **Android SDK** (platform 35)
- Android Studio (recommended) or Gradle CLI

### Building
```bash
./gradlew assembleDebug
```

### Testing
```bash
./gradlew test
./gradlew connectedAndroidTest
```

## Code Guidelines

- Follow Kotlin coding conventions
- Match the existing code style in the project
- Keep functions focused and methods short
- Add comments for complex logic
- Update documentation for user-facing changes

## Pull Request Guidelines

- **Title**: Use a clear, concise description of the change
- **Description**: Explain what changed and why
- **Testing**: Describe how you tested your changes
- **Screenshots**: Include screenshots for UI changes
- Link related issues if applicable

## Reporting Bugs

**For data issues (wrong loot icons, incorrect missions):** Contact `edward_sukuna` on Discord. These issues come from prototrack.gg and cannot be fixed in this app.

**For app bugs:** Open an issue with:
- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Android version and device model
- App version
- Screenshots if applicable

## Feature Requests

Open an issue describing:
- The feature you'd like to see
- Why it would be useful
- How you envision it working

## Questions?

Reach out on Discord: `edward_sukuna`

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

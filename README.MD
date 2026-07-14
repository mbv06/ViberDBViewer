# Viber DB Viewer

[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=26)
[![Tests](https://github.com/mbv06/ViberDBViewer/actions/workflows/tests.yml/badge.svg)](https://github.com/mbv06/ViberDBViewer/actions/workflows/tests.yml)
[![Release](https://github.com/mbv06/ViberDBViewer/actions/workflows/release.yml/badge.svg)](https://github.com/mbv06/ViberDBViewer/actions/workflows/release.yml)

Viber DB Viewer is an offline Android app for browsing Viber SQLite databases. Select a desktop `viber.db` or an Android `viber_messages.db` database with the system file picker; the format is detected automatically and the original file is never modified.

<img width="288" height="462" alt="select chat" src="https://github.com/user-attachments/assets/c1b6c7ee-53b5-4d12-b87e-6f01c4c0c66d" /> <img width="288" height="462" alt="search in chat" src="https://github.com/user-attachments/assets/61ebc017-0425-49e9-be77-e5f6fc2e79ec" />

_This project was created in response to the lack of a convenient way to transfer Viber message history between platforms, export it, and browse it independently of the original app._

## Features

- Personal, group, and self-chat support
- Automatic desktop/Android database format detection
- Chat filtering by contact name or phone number
- Global message search across all chats
- In-chat search with match navigation and highlighting
- Clickable HTTP/HTTPS links
- Text display and placeholders for media, files, deleted messages, and other known message types
- Read-only database access with schema validation and SQLite integrity checks
- Fully offline operation with no Internet permission

## Privacy

Imported databases are copied into the app's private storage and opened read-only. The app does not request Internet access and does not open or extract files referenced by Viber media paths.

## Documentation

- [Database format and compatibility notes](docs/viber-database.md)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

This project is an independent database viewer and is not affiliated with or endorsed by Viber Media S.à r.l.


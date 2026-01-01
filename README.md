# Google Photos Sync

A command-line tool to sync local photo and video folders to Google Photos albums, preserving your directory structure.

## Features

- **Recursive Folder Sync**: Automatically crawls through your directories and uploads supported media.
- **Smart Album Naming**: Converts folder paths into clean Google Photos album names (e.g., `2023/Vacation` becomes `2023 / Vacation`).
- **Resilient Uploads**: Handles batch uploads and provides progress feedback.
- **Image Resizing**: Automatically resizes large images (above 4608px) to optimize storage and upload speed.
- **Performance Optimized**: 
  - Supports `--skip-load` to bypass album listing for faster execution.
  - Optional disk-based albums cache for quick lookups.
- **Media Support**: Automatically identifies `.jpg`, `.jpeg`, `.mp4`, and `.mov` files.

## Prerequisites

- **Java 17** or higher.
- **Google Cloud Project**: You must have a project with the **Google Photos Library API** enabled.
- **Credentials**: A `credentials.json` file (OAuth 2.0 Client ID) from your Google Cloud Console.

## Setup & Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/WerneckPaiva/googlephotos-sync.git
   cd googlephotos-sync
   ```

2. **Add Credentials**:
   Download your `credentials.json` and place it in the `src/main/resources/` directory.

3. **Build the Application**:
   Use the provided Gradle wrapper to build the executable shadow JAR:
   ```bash
   ./gradlew shadowJar
   ```
   The generated JAR will be located at:  
   `build/libs/googlephotos-sync-1.0-SNAPSHOT-uber.jar`

## Usage

Run the sync tool by providing the base folder and (optionally) specific folders to process:

```bash
java -jar build/libs/googlephotos-sync-1.0-SNAPSHOT-uber.jar [OPTIONS] <baseFolder> [foldersToProcess...]
```

### Arguments

- `<baseFolder>`: The root directory of your photo collection. This path is used to determine the relative names of albums.
- `[foldersToProcess...]`: One or more subdirectories to sync. If omitted, the entire `baseFolder` is synced.

### Options

- `--skip-load`: Do not load existing albums from Google Photos at startup. This speeds up the process if you are only adding new albums or know they don't exist yet.
- `--album-id=<ID>`: Force all media to be uploaded to a specific album ID (automatically enables `--skip-load`).
- `--albums-cache=<file>`: Use a local file to cache album information, significantly speeding up multiple runs.
- `-h, --help`: Display help information.
- `-V, --version`: Display version information.

### Examples

**Sync all folders in a directory:**
```bash
java -jar build/libs/googlephotos-sync-1.0-SNAPSHOT-uber.jar /Users/me/Pictures /Users/me/Pictures/2023/Hawaii
```

**Sync a specific subfolder and use a cache:**
```bash
java -jar build/libs/googlephotos-sync-1.0-SNAPSHOT-uber.jar --albums-cache=albums.json /Users/me/Pictures /Users/me/Pictures/2023/Hawaii
```

## Authentication

On the first run, the application will attempt to open your default web browser to authorize access to your Google Photos account. Once authorized, tokens are stored locally in the `credentials/` folder for future use.

## License

This project is open-source. See the repository for license details.

---
Created by [WerneckPaiva](https://github.com/WerneckPaiva)

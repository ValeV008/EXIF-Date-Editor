# EXIF Date Editor

An Android application for editing EXIF metadata and date information in photographs.

## Features

- **Batch EXIF Processing**: Edit EXIF data for multiple images at once
- **Date/Time Picker**: Convenient date and time selection interface
- **Folder Selection**: Browse and select folders containing images
- **Image Preview**: Visual feedback for selected images
- **Progress Tracking**: Real-time progress updates during batch operations
- **Result Summary**: Detailed results of operations including error handling

## Project Structure

```
ExifDateEditor/
├── app/                           # Android app module
│   ├── src/
│   │   └── main/
│   │       ├── java/com/example/exifdateeditor/    # Kotlin source files
│   │       │   ├── MainActivity.kt
│   │       │   ├── BatchExifProcessor.kt
│   │       │   ├── ExifManager.kt
│   │       │   ├── DatePickerDialogFragment.kt
│   │       │   ├── ImagePickerManager.kt
│   │       │   ├── FolderPickerManager.kt
│   │       │   ├── PermissionManager.kt
│   │       │   ├── SelectedImageAdapter.kt
│   │       │   └── ImageMetadata.kt
│   │       ├── res/               # Resources (layouts, colors, styles)
│   │       └── AndroidManifest.xml
│   └── build.gradle              # App-level build configuration
├── gradle/                        # Gradle wrapper
├── build.gradle                  # Project-level build configuration
├── settings.gradle               # Gradle settings
└── README.md                     # This file
```

## Key Components

### MainActivity
The main entry point of the application, handling UI interactions and orchestrating the workflow.

### BatchExifProcessor
Handles batch processing of EXIF data for multiple images with progress tracking and callbacks.

### ExifManager
Manages reading and writing EXIF metadata to image files.

### DatePickerDialogFragment
Provides date and time selection UI for users.

### PermissionManager
Handles runtime permissions required for file access on Android.

### Image Management
- **ImagePickerManager**: Handles image selection from device storage
- **FolderPickerManager**: Enables folder-based image discovery
- **SelectedImageAdapter**: RecyclerView adapter for displaying selected images

## Technologies & Libraries

- **Kotlin**: Primary programming language
- **Android Framework**: Core Android development
- **Gradle**: Build automation and dependency management
- **Android Studio**: Development environment

## Requirements

- Android API Level 13 or higher
- Kotlin 1.x
- Gradle 8.x

## Permissions

The application requires the following permissions:
- Read/Write external storage for accessing and modifying image files
- Calendar permissions for date/time selection

## Usage

1. Launch the application
2. Grant necessary permissions when prompted
3. Select images from your device or browse a folder
4. Choose a new date and time using the date picker
5. Apply the changes to update EXIF metadata
6. View the results of the batch operation

## License

This project is open source and available on GitHub.

## Support

For issues, questions, or contributions, please visit the [GitHub repository](https://github.com/ValeV008/EXIF-Date-Editor).

---

**Note**: This application modifies image EXIF data. Always ensure you have backups of your original images before making batch modifications.
